import numpy as np
from calibrate import *
import sys
data = load(sys.argv[1] if len(sys.argv) > 1 else "features.csv"); g,y,v,ev,logit,jp,q,se = data
base = 1/(1+np.exp(-logit)); order=["original","resize512","screenshot","messenger","jpeg70"]
groups=np.unique(g); rng=np.random.RandomState(0); rng.shuffle(groups)
fo={gr:i%5 for i,gr in enumerate(groups)}; f=np.array([fo[x] for x in g])
cands=np.concatenate([[1e-6,1e-5,1e-4,1e-3],np.linspace(0.005,0.99,200)])
pred=np.zeros(len(g),bool)
for k in range(5):
    tr=(f!=k)&(ev==1)&np.isin(v,order); best=(-1,.5)
    for t in cands:
        p=base[tr]>=t; a=(p==(y[tr]==1)).mean()
        if a>best[0]: best=(a,t)
    te=f==k; pred[te]=base[te]>=best[1]; print(k,"thr",best[1])
for var in order+["ALL"]:
    m=(ev==1)&((v==var) if var!="ALL" else np.isin(v,order))
    p=pred[m]; yy=y[m]
    print(f"{var:11s} FPR {p[yy==0].mean()*100:4.1f}% FNR {(~p[yy==1]).mean()*100:4.1f}% ACC {(p==(yy==1)).mean()*100:4.1f}%")
