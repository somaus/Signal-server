"""평가 세트 이미지에 얼굴 검출을 돌려 검출 수와 지연을 센다. 정답(얼굴 위치)이 없어 정확도가 아니라 검출 현황이다.
사용: EVAL_ROOT=<images/와 meta.csv가 있는 폴더> python eval_faces.py"""
import csv, time, json, sys
from pathlib import Path
from analyzer import FaceAnalyzer
import os
root=Path(os.environ["EVAL_ROOT"]); rows=list(csv.DictReader(open(root/"meta.csv")))
a=FaceAnalyzer(); out=[]; ts=[]
for r in rows:
    d=(root/r["file"]).read_bytes(); t=time.time(); res=a.analyze(d); ts.append(time.time()-t)
    out.append(dict(file=r["file"],label=r["label"],n=len(res["faces"]),faces=res["faces"],w=res["width"],h=res["height"]))
json.dump(out,open("eval_faces.json","w"))
import numpy as np
n=np.array([o["n"] for o in out]); print("images",len(out),"with>=1 face",(n>0).sum(),"multi",(n>1).sum(),"maxfaces",n.max())
print("latency ms p50 %.0f p95 %.0f"%(np.percentile(ts,50)*1000,np.percentile(ts,95)*1000))
for lab in("real","fake"):
    m=[o for o in out if o["label"]==lab]; print(lab,len(m),"faces>=1:",sum(o["n"]>0 for o in m))
