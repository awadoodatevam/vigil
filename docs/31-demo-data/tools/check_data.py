# DD consistency checks DD-K-01..DD-K-14 over docs/31-demo-data. Usage from the repository root: python3 docs/31-demo-data/tools/check_data.py docs/31-demo-data
import sys, os, csv, yaml, itertools, re
D=sys.argv[1]; L=lambda f: yaml.safe_load(open(os.path.join(D,f)))
idn=L('01-tenant-identity.yaml'); sm=L('02-service-map.yaml'); mon=L('03-monitoring.yaml')
ops=L('04-operations-actions-recovery.yaml'); rt=L('05-routing-escalation.yaml'); wf=L('06-workflow.yaml'); st=L('07-settings.yaml')
past=list(csv.DictReader(open(os.path.join(D,'09-past-incidents.csv'))))
rb=open(os.path.join(D,'08-runbooks.md')).read()
res=[]; 
def K(id,ok,msg): res.append((id,ok,msg))
STORM=['disk_full','db_write_errors','ussd_gateway_timeout','balance_api_errors','bundle_api_errors','sms_queue_growth','synthetic_ussd_failed']
# K-01
cat=mon['lab_alert_catalogue']; storm=[a for a in cat if a['alert_type']!='batch_job_failed']
n=sum(a['count'] for a in storm)
def dimcount(a):
    if 'dimensions' in a:
        c=1
        for v in a['dimensions'].values(): c*=len(v)
        return c
    return len(a.get('trigger_names',[]))
K('DD-K-01', 100<=n<=200 and all(dimcount(a)==a['count'] for a in cat) and set(a['alert_type'] for a in storm)==set(STORM) and {a['source'] for a in storm}=={'zabbix','alertmanager'}, f"storm alerts {n}, dimension products match counts, 7 types, both sources")
# service map structures
comps={c['component_key']:c for c in sm['components']}; svcs={s['service_key']:s for s in sm['services']}
deps=[tuple(d) for d in sm['dependencies']]+[(c['component_key'],c.get('runs_on_key')) for c in sm['components'] if c.get('runs_on_key')]
# K-02 identity matching
ik={(k[1],k[2],k[3]):k[0] for k in sm['identity_keys']}
def match(src,pairs):
    for p in pairs:
        if (src,)+p in ik: return ik[(src,)+p]
    return None
intended={'disk_full':'db-core-01','db_write_errors':'subscriber-db','sms_queue_growth':'sms-queue','ussd_gateway_timeout':'ussd-gateway','balance_api_errors':'balance-api','bundle_api_errors':'bundle-api','synthetic_ussd_failed':'ussd-synthetic','batch_job_failed':'reporting-app'}
ok=True; got={}
for a in cat:
    if a['source']=='zabbix': pairs=([('tag.component',a['component_tag'])] if a.get('component_tag') else [])+[('host',a['host'])]
    else: pairs=[('label.component',a['labels']['component'])]
    m=match(a['source'],pairs); got[a['alert_type']]=m; ok&= m==intended[a['alert_type']]
K('DD-K-02',ok,f"lab alerts match components {got}")
# alert type mapping
def maptype(src,name):
    for m in sorted([m for m in mon['alert_type_mappings'] if m['source_kind']==src],key=lambda m:m['order']):
        v=m['value']; o=m['operator']
        if (o=='equals' and name==v) or (o=='contains' and v in name) or (o=='starts_with' and name.startswith(v)): return m['alert_type']
    return 'unclassified'
ok=True
for a in cat:
    names=a.get('trigger_names') or ([a['trigger_name_pattern']] if 'trigger_name_pattern' in a else [a.get('alertname')])
    for nm in names: ok&= maptype(a['source'],nm)==a['alert_type']
K('DD-K-02b',ok,"alert type mappings classify every lab alert as intended")
# K-03 DM-R-55 rules
ok=True; why=[]
keys=[c['component_key'] for c in sm['components']]; ok&=len(keys)==len(set(keys))
for c in sm['components']:
    if c['kind']=='server' and c.get('runs_on_key'): ok=False; why.append(c['component_key'])
    if c.get('runs_on_key') and (c['runs_on_key'] not in comps or comps[c['runs_on_key']]['kind']!='server'): ok=False; why.append(c['component_key'])
    if c.get('target_key') and c['kind']!='server': ok=False
for s in sm['services']:
    ok&= len(s['component_keys'])>=1 and all(k in comps for k in s['component_keys'])
for a,b in deps: ok&= a in comps and b in comps and a!=b
ok&= len(set(ik))==len(sm['identity_keys'])
def reach(x,seen=None):
    seen=seen or set()
    for a,b in deps:
        if a==x and b not in seen: seen.add(b); reach(b,seen)
    return seen
ok&= all(c not in reach(c) for c in comps)
KINDS={'server','application','database','queue','check'}; CRIT={'critical','high','medium','low',None}
ok&= all(c['kind'] in KINDS and c.get('criticality') in CRIT for c in sm['components'])
ok&= all(s_.get('criticality') in CRIT for s_ in sm['services'])
ok&= len({s_['service_key'] for s_ in sm['services']})==len(sm['services'])
ok&= all(k[0] in comps and k[1] in ('zabbix','alertmanager') for k in sm['identity_keys'])
ok&= len({tuple(d) for d in sm['dependencies']})==len(sm['dependencies'])
counts_ok=(len(sm['services']),len(sm['components']),len(sm['identity_keys']),len(sm['dependencies']))==(6,18,12,12)
ok&=counts_ok
K('DD-K-03',ok,f"service map rules DM-R-55 (keys unique, references exist, runs_on/target rules, enum values, no duplicate dependency), DM-E28-R1/R2 (acyclic incl. runs_on); counts 6/18/12/12 as SB-D02: {counts_ok} {why}")
# K-04 grouping
rel=lambda a,b: a==b or b in reach(a) or a in reach(b)
sc=[intended[t] for t in STORM]
# connectivity of storm components under relation
grp={sc[0]}; changed=True
while changed:
    changed=False
    for c in sc:
        if c not in grp and any(rel(c,g) for g in grp): grp.add(c); changed=True
K('DD-K-04', grp==set(sc) and not any(rel('reporting-app',c) for c in sc), f"storm components form one group; reporting-app unrelated to all storm components")
# K-05 hypothesis + routing
counts={c:sum(1 for d in sc if d!=c and c in reach(d)) for c in set(sc)}
order={'critical':4,'high':3,'medium':2,'low':1,None:0}
hyp=sorted(set(sc),key=lambda c:(-counts[c],-order[comps[c].get('criticality')],c))[0]
aff=sorted(s for s,v in svcs.items() if set(v['component_keys'])&set(sc))
def route(hcomp,affs):
    hk=comps[hcomp]['kind']; hc=max([order[svcs[s].get('criticality')] for s in affs] or [0])
    inv={v:k for k,v in order.items()}
    for r in rt['routing_rule_set_revision_1']:
        okr=True
        for c in r['conditions']:
            if c['field']=='highest_service_criticality': okr&= hc>0 and hc>=order[c['value']]
            elif c['field']=='hypothesis_component_kind': okr&= hk in c['value']
            else: raise Exception(c)
        if okr:
            own=comps[hcomp].get('owner_team_key') or r['owner_team_key']
            inf=set(r['informed_team_keys'])|({svcs[s]['owner_team_key'] for s in affs} if r['inform_affected_service_owners'] else set())
            inf.discard(own); return r['rule_key'],r['severity'],own,sorted(inf)
rs=route(hyp,aff); rf=route('reporting-app',['svc-reporting'])
K('DD-K-05', hyp=='db-core-01' and rs[1:]==('P1','db-ops',['vas-service-owners']) and rf[1:3]==('P4','it-infra'), f"hypothesis {hyp} counts {counts}; storm {rs}; SL-F-3 {rf}")
# K-06 impact + title (DM-R-20 computed)
types={t['alert_type']:t['display_name'] for t in mon['alert_types']}
from collections import Counter
def title_for(members, hcomp, n_aff):
    on=[t for t,c in members if c==hcomp]
    cnt=Counter(on) if on else Counter(t for t,c in members)
    prim=sorted(cnt.items(), key=lambda kv:(-kv[1],kv[0]))[0][0]
    t=f"{comps[hcomp]['name']}: {types[prim]}"
    if n_aff>=1: t+=f" — {n_aff} service{'s' if n_aff!=1 else ''} affected"
    return t
storm_members=[(a['alert_type'],intended[a['alert_type']]) for a in storm for _ in range(a['count'])]
title=title_for(storm_members,hyp,len(aff))
aff3=sorted(s_ for s_,v in svcs.items() if 'reporting-app' in v['component_keys'])
title3=title_for([('batch_job_failed','reporting-app')],'reporting-app',len(aff3))
K('DD-K-06', aff==['svc-balance-check','svc-bundle-purchase','svc-sms-notify'] and title=='Subscriber DB server: Disk full — 3 services affected' and title3=='Reporting application: Report export job failed — 1 service affected', f"impact {aff}; titles '{title}' / '{title3}'")
# K-07 workflow validation (SL-WE-08)
def validate(steps,conns):
    ids={s['step_id']:s for s in steps}; out={}
    for f,o,t in conns: out.setdefault(f,[]).append((o,t))
    v=[]
    trig=[s for s in steps if s['type']=='trigger']; 
    if len(trig)!=1: v.append('one_trigger')
    seen=set(); stack=[trig[0]['step_id']]
    while stack:
        x=stack.pop()
        if x in seen: continue
        seen.add(x); stack+= [t for o,t in out.get(x,[])]
    if seen!=set(ids): v.append('reachable')
    for s in steps:
        if s['type']=='condition':
            labels=[b['label'] for b in s['parameters']['branches']]+['otherwise']
            if set(labels)-{o for o,t in out.get(s['step_id'],[])}: v.append('condition_branch_connected')
    # action guarded: every path trigger->action has approval; every path from action outputs reaches recovery
    def paths_to(target,node,has_appr,visited):
        if node==target: return [has_appr]
        r=[]
        for o,t in out.get(node,[]):
            if t not in visited: r+=paths_to(target,t,has_appr or ids[node]['type']=='approval',visited|{t})
        return r
    def ends_without_recovery(node,visited):
        if ids[node]['type']=='recovery_check': return False
        nxt=out.get(node,[])
        outputs={'action':['succeeded','failed'],'ticket':['done','failed'],'escalation':['next'],'approval':['approved','rejected','expired'],'recovery_check':['recovered','not_recovered']}.get(ids[node]['type'])
        if outputs and len(nxt)<len(outputs): return True
        return any(ends_without_recovery(t,visited|{t}) for o,t in nxt if t not in visited)
    for s in steps:
        if s['type']=='action':
            if not all(paths_to(s['step_id'],trig[0]['step_id'],False,{trig[0]['step_id']})): v.append('action_guarded_pre')
            if any(ends_without_recovery(t,{t}) for o,t in out.get(s['step_id'],[])): v.append('action_guarded_post')
    # each (step, output) has at most one connection (DM-V-05)
    if len({(f,o) for f,o,t in conns})!=len(conns): v.append('one_connection_per_output')
    # no_unconnected_step: every non-trigger step has an incoming connection
    inc={t for f,o,t in conns}
    for s_ in steps:
        if s_['type']!='trigger' and s_['step_id'] not in inc: v.append('no_unconnected_step')
    # cycle_has_wait: no cycle at all in these versions, so the rule holds; detect cycles
    def cyc(n,stack):
        for o,t in out.get(n,[]):
            if t in stack: return True
            if cyc(t,stack|{t}): return True
        return False
    if cyc(trig[0]['step_id'],{trig[0]['step_id']}): v.append('cycle_check_needed')
    # parameter_valid (subset): required parameters present and within DM Section 9 bounds
    for s_ in steps:
        p=s_['parameters']; t=s_['type']
        if t=='approval' and not (p.get('eligible_teams') and 60<=p.get('expiry',0)<=86400): v.append('parameter_valid:'+s_['step_id'])
        if t=='recovery_check' and not 60<=p.get('max_wait',0)<=86400: v.append('parameter_valid:'+s_['step_id'])
        if t=='escalation' and p.get('mode')=='wait_for_acknowledgement' and not 30<=p.get('timer',0)<=86400: v.append('parameter_valid:'+s_['step_id'])
        if t=='ai_task' and p.get('task') not in ('SL-T-1','SL-T-2','SL-T-3'): v.append('parameter_valid:'+s_['step_id'])
        if t=='ticket' and p.get('operation') not in ('create','resolve'): v.append('parameter_valid:'+s_['step_id'])
    return v
v1=wf['version_1']; s1=v1['steps']; c1=[tuple(c) for c in v1['connections']]
dc=wf['draft_changes_for_version_2']
pc={(x['step_id'],x['parameter']):x for x in dc['parameter_changes']}
assert all(sid in {x['step_id'] for x in s1} for sid,_ in pc), 'parameter_changes names a step not in version 1'
s2=[]
for x in s1:
    x=dict(x, parameters=dict(x['parameters']))
    for (sid,par),chg in pc.items():
        if sid==x['step_id']:
            assert x['parameters'][par]==chg['from'], 'parameter_changes from-value mismatch'
            x['parameters'][par]=chg['to']
    s2.append(x)
s2+=dc['add_steps']
dels={tuple(c) for c in dc['delete_connections']}
assert dels<=set(c1), 'deleted connection not in version 1'
c2=[c for c in c1 if c not in dels]+[tuple(c) for c in dc['add_connections']]
K('DD-K-07', validate(s1,c1)==[] and validate(s2,c2)==[], f"v1 violations {validate(s1,c1)}; v2 violations {validate(s2,c2)}")
# K-08 runbooks
rbs=[]
for m in re.finditer(r"^## (RB-[A-Z]+-\d+).*?\| applicable_alert_types \| ([^|]+) \|\n\| applicable_component_kinds \| ([^|]+) \|",rb,re.S|re.M):
    rbs.append((m.group(1),[x.strip() for x in m.group(2).split(',')],m.group(3).strip()))
app=[(len(set(t)&set(STORM)),k) for k,t,kinds in rbs if set(t)&set(STORM) and (kinds=='(any)' or 'server' in kinds)]
top=[k for c,k in sorted(app,key=lambda x:(-x[0],x[1]))][:st['knowledge_settings']['runbook_limit']]
K('DD-K-08', top==['RB-DB-001','RB-API-003','RB-SMS-004'], f"runbooks for storm {top}")
# K-09 search text
def stext(types_,svcs_,comp): return f"alert types: {', '.join(sorted(types_))}; services: {', '.join(sorted(svcs_))}; component: {comp}"
storm_t=stext(STORM,aff,hyp)
same=[p['past_incident_key'] for p in past if stext(p['alert_types'].split(';'),[x for x in p['affected_service_keys'].split(';') if x],p['hypothesis_component_key'])==storm_t]
sl3=stext(['batch_job_failed'],['svc-reporting'],'reporting-app')
same3=[p['past_incident_key'] for p in past if p['hypothesis_component_key']=='reporting-app' or 'batch_job_failed' in p['alert_types']]
pi=[p for p in past if p['past_incident_key']=='PI-0342'][0]
K('DD-K-09', same==['PI-0342'] and not same3 and pi['fix_action_key']=='purge-archived-txlogs' and pi['fix_success_count']=='4' and sorted(p['past_incident_key'] for p in past if p['fix_action_key']=='purge-archived-txlogs')==['PI-0303','PI-0306','PI-0327','PI-0342'] and all(p['fix_success_count']=='4' for p in past if p['fix_action_key']), f"storm search text identical only to {same}; no past incident on reporting-app/batch_job_failed; storm text '{storm_t}'")
# K-10 cross-module keys
teams={t['team_key'] for t in idn['teams']}; users={u['username'] for u in idn['users']}
atypes={t['alert_type'] for t in mon['alert_types']}; opk={o['operation_key'] for o in ops['operations']}
diag={d['diagnostic_key'] for d in ops['diagnostic_definitions']}; acts={a['action_key'] for a in ops['registered_actions']}
rcs={r['recovery_check_key'] for r in ops['recovery_check_definitions']}; provs={p['provider_key'] for p in st['ai_provider_configurations']}
bad=[]
for c in sm['components']:
    if c.get('owner_team_key') and c['owner_team_key'] not in teams: bad.append(c['component_key'])
    if c.get('target_key') and c['target_key'] not in {g['target_key'] for g in ops['target_access_grants']}: bad.append('target')
for s in sm['services']:
    if s.get('owner_team_key') not in teams: bad.append(s['service_key'])
for r in rt['routing_rule_set_revision_1']:
    if r['owner_team_key'] not in teams or not set(r['informed_team_keys'])<=teams: bad.append(r['rule_key'])
for l in rt['escalation_policy_revision_1']['levels']:
    if not set(l['team_keys'])<=teams: bad.append('esc')
for d in st['delivery_addresses']:
    if d['subject_key'] not in (teams if d['subject_kind']=='team' else users): bad.append(d['subject_key'])
for s in s2:
    p=s.get('parameters',{})
    for t in p.get('eligible_teams',[])+p.get('named_teams',[]):
        if t not in teams: bad.append(s['step_id'])
    for d_ in p.get('diagnostics',[]):
        if d_ not in diag: bad.append(d_)
for p in past:
    if not set(p['alert_types'].split(';'))<=atypes: bad.append(p['past_incident_key'])
    if p['hypothesis_component_key'] not in comps: bad.append(p['past_incident_key'])
    if not {x for x in p['affected_service_keys'].split(';') if x}<=set(svcs): bad.append(p['past_incident_key'])
    if p['fix_action_key'] and p['fix_action_key'] not in acts: bad.append(p['past_incident_key'])
for d_ in ops['diagnostic_definitions']:
    if d_['operation_key'] not in opk: bad.append(d_['diagnostic_key'])
for a in ops['registered_actions']:
    if a['operation_key'] not in opk or a['precondition']['operation_key'] not in opk or a['reconciliation']['operation_key'] not in opk or a['recovery_check_key'] not in rcs: bad.append(a['action_key'])
for r in ops['recovery_check_definitions']:
    for c in r['conditions']:
        if c.get('component_key') and c['component_key'] not in comps: bad.append(r['recovery_check_key'])
for t,lst in st['task_assignments'].items():
    if not set(lst)<=provs: bad.append(t)
for c,lst in st['data_routing_rules'].items():
    if not set(lst)<=provs: bad.append(c)
if st['knowledge_settings']['embedding_provider_key'] not in provs: bad.append('ks')
for m in re.finditer(r"applicable_alert_types \| ([^|]+) \|",rb):
    if not {x.strip() for x in m.group(1).split(',')}<=atypes: bad.append('runbook')
for u,g in idn['permission_grants'].items():
    if u not in users: bad.append(u)
for m in idn['team_memberships']:
    if m['team_key'] not in teams or m['username'] not in users: bad.append('membership')
handles={c['handle'] for c in st['credential_references']}; cinst={c['handle'] for c in st['connector_instances']}
credp={c['handle']:c['purpose'] for c in st['credential_references']}
for c in st['connector_instances']:
    if c['credential_ref'] and c['credential_ref'] not in handles: bad.append(c['handle'])
for m_ in mon['monitoring_sources']:
    if m_['connector_instance'] not in cinst or credp.get(m_['webhook_token_ref'])!='webhook_token': bad.append(m_['name'])
for g in ops['target_access_grants']:
    if g['connector_instance'] not in cinst or credp.get(g['credential_ref'])!={'diagnostic':'target_diagnostic','action':'target_action'}[g['permission_kind']]: bad.append('grant')
if len({g['credential_ref'] for g in ops['target_access_grants']})!=2: bad.append('grant-creds')
kind={p_['provider_key']:p_['provider_kind'] for p_ in st['ai_provider_configurations']}
for p_ in st['ai_provider_configurations']:
    if p_['connector_instance'] not in cinst: bad.append(p_['provider_key'])
for t,lst in st['task_assignments'].items():
    if not all(kind[x] in st['allowed_providers'][t] for x in lst): bad.append('task '+t)
if len(st['task_assignments']['SL-T-3'])!=1: bad.append('SL-T-3 single provider')
for ap in ops['action_policies']:
    if ap['action_key'] not in acts or (ap['service_key'] and ap['service_key'] not in svcs): bad.append('policy')
for m_ in mon['alert_type_mappings']:
    if m_['alert_type'] not in atypes: bad.append('mapping '+m_['alert_type'])
K('DD-K-10', not bad and len(past)==50, f"cross-module keys (DM-R-60); problems {bad}")
# K-11 dashboard seeded values
tta=sum(int(p['acknowledged_after_minutes']) for p in past)/50; ttr=sum(int(p['recovered_after_minutes']) for p in past)/50
al=sum(int(p['alert_count']) for p in past); mg=sum(1 for p in past if int(p['alert_count'])>=2)
K('DD-K-11', (round(tta,1),round(ttr,1),al,al-50,mg)==(4.3,28.2,684,634,46), f"seeded-only dashboard: TTA {tta:.2f} min, TTR {ttr:.2f} min, alerts {al}, suppressed {al-50}, merged {mg}")
# K-12 readiness gaps
gaps=[c['component_key'] for c in sm['components'] if c['kind']=='server' and (not c.get('owner_team_key') or c['owner_team_key'] not in teams)]+[s['service_key'] for s in sm['services'] if not s.get('criticality')]
K('DD-K-12', gaps==['bkp-srv-01','svc-reporting'], f"readiness gaps {gaps} (sources healthy in starting state)")
# K-13 approvers
appr=[m['username'] for m in idn['team_memberships'] if m['team_key']=='db-ops' and 'approve_action' in idn['permission_grants'][m['username']]]
K('DD-K-13', appr==['dbops-engineer','dbops-lead'], f"eligible approvers {appr}")
# K-14 timing constraints
src=mon['monitoring_sources']; cs=st['correlation_settings']; rc=ops['recovery_check_definitions']
maxwait=[x['parameters']['max_wait'] for x in s1 if x['type']=='recovery_check']
e1=[x['parameters']['timer'] for x in s1 if x['step_id']=='e1'][0]
okc= all(s_['health_check_interval_s']<s_['stale_after_s']<s_['disconnected_after_s'] for s_ in src) and cs['settle_period_s']<cs['max_forming_s'] \
  and cs['settle_period_s']>30 and cs['stability_check_interval_s']<=5 and all(r['observation_window_s']<min(maxwait) for r in rc) \
  and st['incident_settings']['closure_check_interval_s']<=60 and e1==600
K('DD-K-14', okc, f"health interval < stale < disconnected; 30 s max storm gap < settle {cs['settle_period_s']} s < max forming; window < max_wait {maxwait}; e1 timer {e1} s; check intervals within limits")
w=max(len(r[0]) for r in res)
for id,ok,msg in res: print(f"{id:<10} {'PASS' if ok else 'FAIL'}  {msg}")
print("ALL PASS" if all(r[1] for r in res) else "FAILURES")
sys.exit(0 if all(r[1] for r in res) else 1)
