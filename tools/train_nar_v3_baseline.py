#!/usr/bin/env python3
import argparse, gzip, hashlib, json, platform
from pathlib import Path
import lightgbm as lgb
import numpy as np
import pandas as pd
import sklearn
from sklearn.metrics import brier_score_loss, log_loss, roc_auc_score

MODEL='nar-v3-win-baseline9'; TARGET='label_win'; PRIMARY_METRIC='binary_logloss'; REPORTED_METRICS=['binary_logloss','auc']
FEATURES=['race_month','race_day_of_year','race_weekday_mon0','age_days','feature_race__競馬場','feature_entry__毛色','feature_entry__父馬名','feature_entry__母馬名','feature_entry__母父馬名']
CATS=FEATURES[4:]
LABELS=['label_result_status','label_numeric_finish_position','label_order_valid','label_started','label_finished','label_win','label_top2','label_top3']
POLICY={'fit_on':['train'],'early_stopping_on':['validation'],'feature_selection_on':['validation'],'test_use':'final_evaluation_only','out_of_time_use':'post_final_evaluation_only'}

def sha256(p):
    h=hashlib.sha256()
    with Path(p).open('rb') as f:
        for c in iter(lambda:f.read(1024*1024),b''): h.update(c)
    return h.hexdigest()

def loadj(p): return json.loads(Path(p).read_text(encoding='utf-8'))
def writej(p,x): Path(p).write_text(json.dumps(x,ensure_ascii=False,indent=2,sort_keys=True)+'\n',encoding='utf-8',newline='\n')

def months(a,b):
    y,m=int(a[:4]),int(a[4:]); ey,em=int(b[:4]),int(b[4:]); out=[]
    while (y,m)<=(ey,em):
        out.append(f'{y:04d}{m:02d}'); m+=1
        if m==13: y+=1; m=1
    return out

def allowed_months(): return {'train':months('202101','202312'),'validation':months('202401','202412')}
def split_for(ym):
    y=int(ym[:4])
    if 2021<=y<=2023:return 'train'
    if y==2024:return 'validation'
    raise ValueError(f'forbidden month for baseline trainer: {ym}')

def validate_config(c):
    if c.get('version')!=3 or c.get('model_name')!=MODEL or c.get('target')!=TARGET: raise ValueError('config identity mismatch')
    i=c.get('input',{})
    if i.get('dataset')!='nar-v3-model-input' or i.get('feature_count')!=9 or i.get('categorical_feature_indices')!=[4,5,6,7,8]: raise ValueError('input contract mismatch')
    if c.get('splits')!={'train':'train','validation':'validation','test':'test','out_of_time':'out_of_time'}: raise ValueError('split contract mismatch')
    if c.get('selection_policy')!=POLICY: raise ValueError('selection policy mismatch')
    if set(c.get('expected_rows',{}))!={'train','validation','test','out_of_time'}: raise ValueError('expected_rows mismatch')
    t=c.get('training',{})
    if any(not isinstance(t.get(k),int) or t[k]<=0 for k in ('num_boost_round','early_stopping_rounds','log_evaluation_period')): raise ValueError('training contract mismatch')
    if t.get('early_stopping_metric')!=PRIMARY_METRIC: raise ValueError('early stopping metric mismatch')
    p=c.get('lightgbm_params',{})
    if p.get('objective')!='binary' or p.get('deterministic') is not True or p.get('force_col_wise') is not True: raise ValueError('LightGBM safety contract mismatch')
    if p.get('metric')!=REPORTED_METRICS: raise ValueError('LightGBM metric order mismatch')
    if c.get('evaluation')!={'calibration_bins':20,'race_top_k':[1,2,3]}: raise ValueError('evaluation contract mismatch')

def validate_checkpoint(c,cp):
    if cp.get('format_version')!=1 or cp.get('dataset')!='nar-v3-model-input': raise ValueError('checkpoint identity mismatch')
    if cp.get('period')!={'start_ym':'202101','end_ym':'202607','months':67}: raise ValueError('checkpoint period mismatch')
    if cp.get('transform_root_hash',{}).get('value')!=c['input']['transform_root_sha256']: raise ValueError('transform root hash mismatch')
    if cp.get('audit')!={'full_transform_audit':'PASS'}: raise ValueError('checkpoint audit not PASS')

def verify_artifacts(root,cp):
    fo=Path(root)/'artifacts'/'feature-order.json'; cd=Path(root)/'artifacts'/'category-dictionaries.json'
    if sha256(fo)!=cp['artifacts']['feature_order_sha256']: raise ValueError('feature-order SHA mismatch')
    if sha256(cd)!=cp['artifacts']['category_dictionary_sha256']: raise ValueError('category dictionary SHA mismatch')
    obj=loadj(fo)
    if obj.get('feature_count')!=9 or obj.get('categorical_feature_indices')!=[4,5,6,7,8] or obj.get('categorical_feature_names')!=CATS: raise ValueError('feature-order contract mismatch')
    if [x.get('name') for x in obj.get('features',[])]!=FEATURES: raise ValueError('feature names mismatch')
    return fo,cd

def csvpath(root,ym): return Path(root)/'monthly'/ym[:4]/f'{ym}_model.csv.gz'
def manpath(root,ym): return Path(root)/'monthly'/ym[:4]/f'{ym}_model.manifest.json'

def verify_month(root,ym,split):
    if split_for(ym)!=split: raise ValueError('month/split mismatch')
    p=csvpath(root,ym); m=manpath(root,ym)
    if not p.is_file() or not m.is_file(): raise FileNotFoundError(ym)
    text=m.read_text(encoding='utf-8')
    if 'C:\\' in text or 'C:/' in text: raise ValueError(f'{ym}: absolute path leaked')
    j=json.loads(text)
    if j.get('dataset')!='nar-v3-model-input' or j.get('source_ym')!=ym or j.get('output_file')!=p.name: raise ValueError(f'{ym}: manifest identity mismatch')
    if j.get('output_sha256')!=sha256(p) or j.get('output_bytes')!=p.stat().st_size: raise ValueError(f'{ym}: manifest file mismatch')
    if j.get('feature_columns')!=FEATURES or j.get('label_columns')!=LABELS: raise ValueError(f'{ym}: schema mismatch')
    n=j.get('counts',{}).get('rows')
    if not isinstance(n,int) or n<=0: raise ValueError(f'{ym}: bad row count')
    return p,n

def load_split(root,split,ym_list):
    use=['race_id','entry_id','source_ym','split',*FEATURES,TARGET]
    dtype={x:'int32' for x in FEATURES}; dtype.update({'race_id':'string','entry_id':'string','source_ym':'string','split':'string',TARGET:'float32'})
    frames=[]; expected=0
    for i,ym in enumerate(ym_list,1):
        p,n=verify_month(root,ym,split)
        f=pd.read_csv(p,compression='gzip',usecols=use,dtype=dtype,keep_default_na=True)
        if len(f)!=n: raise ValueError(f'{ym}: row count mismatch')
        if not (f.source_ym.astype(str)==ym).all() or not (f['split']==split).all(): raise ValueError(f'{ym}: row split/source mismatch')
        if f[['race_id','entry_id',*FEATURES]].isna().any().any(): raise ValueError(f'{ym}: missing IDs/features')
        if (f[CATS]<0).any().any(): raise ValueError(f'{ym}: negative category id')
        if not f[TARGET].dropna().isin([0.0,1.0]).all(): raise ValueError(f'{ym}: invalid target')
        frames.append(f); expected+=n
        if i%12==0 or i==len(ym_list): print(f'loaded {split} {i}/{len(ym_list)} through {ym}')
    f=pd.concat(frames,ignore_index=True)
    if len(f)!=expected or f.entry_id.duplicated().any(): raise ValueError(f'{split}: concat/duplicate failure')
    good=f[TARGET].notna(); masked=int((~good).sum()); f=f.loc[good].reset_index(drop=True); f[TARGET]=f[TARGET].astype('int8')
    pos=int(f[TARGET].sum()); stats={'source':expected,'masked':masked,'supervised':len(f),'positive':pos,'negative':len(f)-pos}
    print(split,stats)
    return f,stats

def metrics(y,p):
    y=np.asarray(y,dtype=np.int8); p=np.asarray(p,dtype=np.float64)
    return {'rows':len(y),'positive':int(y.sum()),'negative':int(len(y)-y.sum()),'log_loss':float(log_loss(y,p,labels=[0,1])),'brier_score':float(brier_score_loss(y,p)),'roc_auc':float(roc_auc_score(y,p)),'prediction_min':float(p.min()),'prediction_max':float(p.max()),'prediction_mean':float(p.mean())}

def race_metrics(frame,p):
    z=pd.DataFrame({'race_id':frame.race_id,'entry_id':frame.entry_id,'label':frame[TARGET].to_numpy(),'prediction':p})
    hits={1:0,2:0,3:0}; sums=[]; sizes=[]; multi=0; races=0
    for _,g in z.groupby('race_id',sort=False):
        races+=1; g=g.sort_values(['prediction','entry_id'],ascending=[False,True],kind='mergesort'); w=int(g.label.sum())
        if w<1: raise ValueError('supervised race without winner')
        if w>1: multi+=1
        sums.append(float(g.prediction.sum())); sizes.append(len(g))
        for k in hits:
            if g.head(k).label.sum()>0:hits[k]+=1
    a=np.asarray(sums); s=np.asarray(sizes)
    return {'races':races,'top1_winner_rate':hits[1]/races,'top2_winner_inclusion_rate':hits[2]/races,'top3_winner_inclusion_rate':hits[3]/races,'race_probability_sum':{'mean':float(a.mean()),'median':float(np.median(a)),'min':float(a.min()),'max':float(a.max())},'field_size':{'mean':float(s.mean()),'min':int(s.min()),'max':int(s.max())},'races_with_multiple_winners':multi}

def evaluate(frame,model,best):
    p=model.predict(frame[FEATURES],num_iteration=best); y=frame[TARGET].to_numpy(dtype=np.int8)
    return {'global':metrics(y,p),'race':race_metrics(frame,p)},p

def main():
    ap=argparse.ArgumentParser(); ap.add_argument('--dataset-root',required=True,type=Path); ap.add_argument('--config',required=True,type=Path); ap.add_argument('--transform-checkpoint',required=True,type=Path); ap.add_argument('--out',required=True,type=Path); ap.add_argument('--preflight-only',action='store_true'); a=ap.parse_args()
    if not a.preflight_only and a.out.exists(): raise FileExistsError(f'output already exists: {a.out}')
    c=loadj(a.config); cp=loadj(a.transform_checkpoint); validate_config(c); validate_checkpoint(c,cp); fo,cd=verify_artifacts(a.dataset_root,cp)
    am=allowed_months(); print('trainer access policy = train + validation only'); print('test months opened = 0'); print('out_of_time months opened = 0')
    tr,ts=load_split(a.dataset_root,'train',am['train']); va,vs=load_split(a.dataset_root,'validation',am['validation'])
    if ts!=c['expected_rows']['train'] or vs!=c['expected_rows']['validation']: raise ValueError('expected row accounting mismatch')
    if set(tr.entry_id).intersection(set(va.entry_id)): raise ValueError('train/validation overlap')
    print('TRAIN/VALIDATION PREFLIGHT OK')
    if a.preflight_only:return
    a.out.mkdir(parents=True,exist_ok=False)
    dtr=lgb.Dataset(tr[FEATURES],label=tr[TARGET],categorical_feature=CATS,free_raw_data=False); dva=lgb.Dataset(va[FEATURES],label=va[TARGET],reference=dtr,categorical_feature=CATS,free_raw_data=False)
    tc=c['training']; print('primary selection metric =',PRIMARY_METRIC); print('early stopping first metric only = YES'); model=lgb.train(c['lightgbm_params'],dtr,num_boost_round=tc['num_boost_round'],valid_sets=[dva],valid_names=['validation'],callbacks=[lgb.early_stopping(tc['early_stopping_rounds'],first_metric_only=True,verbose=True),lgb.log_evaluation(tc['log_evaluation_period'])])
    best=int(model.best_iteration)
    if best<=0: raise ValueError('invalid best_iteration')
    trm,trp=evaluate(tr,model,best); vam,vap=evaluate(va,model,best)
    mp=a.out/'model.txt'; model.save_model(str(mp),num_iteration=best)
    for name,frame,pred in [('train',tr,trp),('validation',va,vap)]: pd.DataFrame({'race_id':frame.race_id,'entry_id':frame.entry_id,'label_win':frame[TARGET],'prediction':pred}).to_csv(a.out/f'{name}-predictions.csv.gz',index=False,compression={'method':'gzip','mtime':0},lineterminator='\n')
    result={'model_name':MODEL,'target':TARGET,'selection_state':'PROVISIONAL_VALIDATION_ONLY','model_selection':{'primary_metric':PRIMARY_METRIC,'reported_metrics':REPORTED_METRICS,'selection_split':'validation','early_stopping_first_metric_only':True},'best_iteration':best,'source_rows':{'train':ts['source'],'validation':vs['source']},'masked_target_rows':{'train':ts['masked'],'validation':vs['masked']},'supervised_rows':{'train':ts['supervised'],'validation':vs['supervised']},'train':trm,'validation':vam,'runtime':{'python':platform.python_version(),'lightgbm':lgb.__version__,'numpy':np.__version__,'pandas':pd.__version__,'scikit_learn':sklearn.__version__},'access_policy':{'train_months_opened':36,'validation_months_opened':12,'test_months_opened':0,'out_of_time_months_opened':0},'sha256':{'training_config':sha256(a.config),'transform_checkpoint':sha256(a.transform_checkpoint),'feature_order':sha256(fo),'category_dictionaries':sha256(cd),'trainer':sha256(Path(__file__).resolve()),'model':sha256(mp)}}
    writej(a.out/'metrics.json',result); writej(a.out/'model-metadata.json',{'version':3,'model_name':MODEL,'target':TARGET,'selection_state':'PROVISIONAL_VALIDATION_ONLY','model_selection':{'primary_metric':PRIMARY_METRIC,'reported_metrics':REPORTED_METRICS,'selection_split':'validation','early_stopping_first_metric_only':True},'feature_count':9,'categorical_feature_count':5,'best_iteration':best,'model_file':'model.txt','model_sha256':sha256(mp)})
    print('model_sha256 =',sha256(mp)); print('best_iteration =',best); print('test evaluated = NO'); print('out_of_time evaluated = NO'); print('NAR V3 BASELINE9 VALIDATION TRAINING OK')

if __name__=='__main__': main()
