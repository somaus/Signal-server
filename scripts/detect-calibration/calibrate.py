"""압축 인지 보정(calibration) 학습·평가. 원본 단위 GroupKFold로 누수를 막는다."""
import csv, sys, json
import numpy as np

def load(path):
    rows = list(csv.DictReader(open(path)))
    g = np.array([r["group"] for r in rows]); y = np.array([int(r["label"]) for r in rows])
    v = np.array([r["variant"] for r in rows]); ev = np.array([int(r["eval"]) for r in rows])
    logit = np.array([float(r["logit"]) for r in rows]); jp = np.array([int(r["jpeg"]) for r in rows])
    q = np.array([float(r["quality"]) for r in rows]); se = np.array([float(r["short_edge"]) for r in rows])
    return g, y, v, ev, logit, jp, q, se

def feats(kind, logit, jp, q, se):
    z = np.clip(logit, -20, 20) / 5.0
    sev = jp * (100.0 - q) / 50.0              # JPEG 압축 강도(0=무압축/비JPEG)
    if kind == "logit":   return np.c_[z]
    if kind == "jpeg":    return np.c_[z, jp, sev]
    if kind == "jpeg_x":  return np.c_[z, jp, sev, z * sev, z * jp]
    if kind == "full":    return np.c_[z, jp, sev, z * sev, z * jp, np.log(se / 512.0)]
    raise ValueError(kind)

def fit(X, y, l2=1.0, iters=50):
    Xb = np.c_[np.ones(len(X)), X]; w = np.zeros(Xb.shape[1])
    R = np.eye(len(w)) * l2; R[0, 0] = 0
    for _ in range(iters):
        p = 1 / (1 + np.exp(-Xb @ w))
        g = Xb.T @ (p - y) + R @ w
        H = (Xb * (p * (1 - p))[:, None]).T @ Xb + R + 1e-8 * np.eye(len(w))
        step = np.linalg.solve(H, g); w -= step
        if np.abs(step).max() < 1e-8: break
    return w

def predict(w, X):
    return 1 / (1 + np.exp(-(np.c_[np.ones(len(X)), X] @ w)))

def auc(s, y):
    pos, neg = s[y == 1], s[y == 0]
    gt = (pos[:, None] > neg[None, :]).sum() + 0.5 * (pos[:, None] == neg[None, :]).sum()
    return gt / (len(pos) * len(neg))

def tpr_at_fpr(s, y, fpr):
    neg = np.sort(s[y == 0])[::-1]; k = int(np.floor(fpr * len(neg)))
    thr = neg[k] if k < len(neg) else -1
    return float((s[y == 1] > thr).mean())

def metrics(s, y, thr=0.5):
    pred = s >= thr
    fpr = float(pred[y == 0].mean()); fnr = float((~pred[y == 1]).mean())
    return dict(auc=auc(s, y), fpr=fpr, fnr=fnr, acc=float((pred == (y == 1)).mean()),
                tpr1=tpr_at_fpr(s, y, 0.01), tpr5=tpr_at_fpr(s, y, 0.05))

def bands(s, y, se, low=10, high=70, minedge=256):
    sc = s * 100; out = {}
    for lab in (0, 1):
        m = y == lab; sm = sc[m]; und = se[m] < minedge
        out[lab] = dict(low=int(((sm < low) & ~und).sum()), unc=int(((sm >= low) & (sm <= high) & ~und).sum()),
                        high=int(((sm > high) & ~und).sum()), und=int(und.sum()))
    return out

def cv(kind, data, folds=5, l2=1.0, seed=0):
    g, y, v, ev, logit, jp, q, se = data
    groups = np.unique(g); rng = np.random.RandomState(seed); rng.shuffle(groups)
    fold_of = {gr: i % folds for i, gr in enumerate(groups)}
    f = np.array([fold_of[x] for x in g]); pred = np.full(len(g), np.nan)
    X = feats(kind, logit, jp, q, se)
    for k in range(folds):
        tr = f != k; te = (f == k)
        w = fit(X[tr], y[tr], l2); pred[te] = predict(w, X[te])
    return pred

if __name__ == "__main__":
    data = load(sys.argv[1]); g, y, v, ev, logit, jp, q, se = data
    base = 1 / (1 + np.exp(-logit))
    res = {}
    order = ["original", "resize512", "screenshot", "messenger", "jpeg70"]
    kinds = ["logit", "jpeg", "jpeg_x", "full"]
    preds = {k: cv(k, data) for k in kinds}
    print("== 변형별 (평가 변형만, 원본 단위 5-fold CV 예측) ==")
    for name, s_all in [("baseline", base)] + [(k, preds[k]) for k in kinds]:
        print("\n#", name)
        for var in order:
            m = (v == var) & (ev == 1)
            r = metrics(s_all[m], y[m]); res[(name, var)] = r
            print(f"{var:11s} AUC {r['auc']:.3f} FPR {r['fpr']*100:4.1f}% FNR {r['fnr']*100:4.1f}% ACC {r['acc']*100:4.1f}% TPR@1% {r['tpr1']*100:4.1f}% TPR@5% {r['tpr5']*100:4.1f}%")
        m = (ev == 1) & np.isin(v, order)
        r = metrics(s_all[m], y[m]); print(f"{'ALL':11s} AUC {r['auc']:.3f} FPR {r['fpr']*100:4.1f}% FNR {r['fnr']*100:4.1f}% ACC {r['acc']*100:4.1f}% TPR@1% {r['tpr1']*100:4.1f}% TPR@5% {r['tpr5']*100:4.1f}%")
    json.dump({f"{a}|{b}": r for (a, b), r in res.items()}, open("cv_results.json", "w"), indent=1)
