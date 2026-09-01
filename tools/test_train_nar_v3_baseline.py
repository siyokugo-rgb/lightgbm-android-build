#!/usr/bin/env python3
import importlib.util, unittest
from pathlib import Path
P=Path(__file__).with_name('train_nar_v3_baseline.py'); S=importlib.util.spec_from_file_location('t',P); t=importlib.util.module_from_spec(S); S.loader.exec_module(t)

def cfg():
    return {'version':3,'model_name':'nar-v3-win-baseline9','target':'label_win','input':{'dataset':'nar-v3-model-input','transform_root_sha256':'a'*64,'feature_count':9,'categorical_feature_indices':[4,5,6,7,8]},'splits':{'train':'train','validation':'validation','test':'test','out_of_time':'out_of_time'},'selection_policy':dict(t.POLICY),'expected_rows':{'train':{},'validation':{},'test':{},'out_of_time':{}},'training':{'num_boost_round':2000,'early_stopping_rounds':100,'log_evaluation_period':50},'lightgbm_params':{'objective':'binary','deterministic':True,'force_col_wise':True},'evaluation':{'calibration_bins':20,'race_top_k':[1,2,3]}}

def cp(): return {'format_version':1,'dataset':'nar-v3-model-input','period':{'start_ym':'202101','end_ym':'202607','months':67},'transform_root_hash':{'value':'a'*64},'audit':{'full_transform_audit':'PASS'}}

class T(unittest.TestCase):
    def test_months(self):
        m=t.allowed_months(); self.assertEqual(len(m['train']),36); self.assertEqual(len(m['validation']),12); self.assertFalse(any(x.startswith(('2025','2026')) for x in m['train']+m['validation']))
    def test_forbidden_test(self):
        with self.assertRaises(ValueError): t.split_for('202501')
    def test_forbidden_oot(self):
        with self.assertRaises(ValueError): t.split_for('202607')
    def test_valid_config(self): t.validate_config(cfg())
    def test_test_cannot_fit(self):
        c=cfg(); c['selection_policy']['fit_on']=['train','test']
        with self.assertRaises(ValueError): t.validate_config(c)
    def test_oot_cannot_early_stop(self):
        c=cfg(); c['selection_policy']['early_stopping_on']=['out_of_time']
        with self.assertRaises(ValueError): t.validate_config(c)
    def test_test_cannot_select_features(self):
        c=cfg(); c['selection_policy']['feature_selection_on']=['validation','test']
        with self.assertRaises(ValueError): t.validate_config(c)
    def test_checkpoint_hash(self):
        c=cfg(); x=cp(); x['transform_root_hash']['value']='b'*64
        with self.assertRaises(ValueError): t.validate_checkpoint(c,x)
    def test_checkpoint_audit(self):
        c=cfg(); x=cp(); x['audit']={'full_transform_audit':'FAIL'}
        with self.assertRaises(ValueError): t.validate_checkpoint(c,x)
    def test_month_sequence(self): self.assertEqual(t.months('202311','202402'),['202311','202312','202401','202402'])
if __name__=='__main__': unittest.main()
