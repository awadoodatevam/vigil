# Generates DD-F-09 past incidents deterministically. Run from docs/31-demo-data: python3 tools/gen_past.py 09-past-incidents.csv (output is committed; re-running gives the same file)
import csv, random, sys
random.seed(20261009)
T = [  # (template id, count, title, alert_types, services, component, alert_count range, fix description, fix_action_key, success_count, summary)
 ("T02",3,"Subscriber DB /data nearly full — early warning",["disk_full","db_write_errors"],["svc-balance-check","svc-bundle-purchase"],"db-core-01",(6,14),"Purged archived transaction logs older than 24 hours.","purge-archived-txlogs",4,"Caught at 97% /data; a few DB write errors before the purge."),
 ("T03",4,"Subscriber DB lock waits slow balance and bundle APIs",["db_lock_waits","api_latency_high"],["svc-balance-check","svc-bundle-purchase"],"subscriber-db",(10,30),"DB Ops killed the blocking reporting session after checking with its owner.","",0,"Long-running ad-hoc query held row locks during peak hours."),
 ("T04",3,"Subscriber DB connection pool exhausted",["db_connections_exhausted","balance_api_errors"],["svc-balance-check"],"subscriber-db",(12,26),"IT Applications recycled the balance API pool; DB Ops raised the session limit by change request.","",0,"A connection leak in the balance API exhausted the DB pool."),
 ("T05",5,"SMSC bind lost on several routes",["smsc_bind_lost","sms_queue_growth"],["svc-sms-notify"],"sms-queue",(8,22),"VAS Operations re-bound the affected SMSC routes.","",0,"SMSC peer restarted; binds were not re-established automatically."),
 ("T06",4,"VAS application server memory high, USSD timeouts",["memory_high","ussd_gateway_timeout"],["svc-balance-check","svc-bundle-purchase"],"vas-app-01",(14,40),"VAS Operations restarted the USSD gateway node after peak; memory leak fix tracked by vendor case.","",0,"Gateway node memory grew until sessions timed out."),
 ("T07",4,"API application server CPU high",["cpu_high","api_latency_high"],["svc-balance-check","svc-bundle-purchase","svc-recharge"],"api-app-01",(9,24),"IT Applications moved a batch export off the API server.","",0,"A misplaced batch export competed with API traffic."),
 ("T08",4,"Billing DB lock waits, recharge API errors",["recharge_api_errors","db_lock_waits"],["svc-recharge"],"billing-db",(6,18),"DB Ops terminated the blocking session and rescheduled the billing job.","",0,"Billing job overlapped with the evening recharge peak."),
 ("T09",3,"Self-care portal TLS certificate expiring",["cert_expiring"],["svc-self-care"],"self-care-portal",(1,2),"IT Applications renewed and deployed the certificate.","",0,"Certificate expiry warning 14 days ahead."),
 ("T10",3,"Self-care portal unavailable",["portal_unavailable","api_latency_high"],["svc-self-care"],"self-care-portal",(4,12),"IT Applications restarted the portal service after a failed deployment.","",0,"Portal returned errors after a deployment."),
 ("T11",5,"Bundle-purchase API errors on one instance",["bundle_api_errors"],["svc-bundle-purchase"],"bundle-api",(3,9),"IT Applications removed the instance from the pool and restarted it.","",0,"One API instance lost its cache connection."),
 ("T12",3,"Backup job failed on backup server",["backup_failed"],[],"backup-agent",(1,1),"IT Infrastructure re-ran the backup in the next window.","",0,"Backup target share was full."),
 ("T13",3,"Synthetic USSD failures from one city",["synthetic_ussd_failed"],["svc-balance-check"],"ussd-synthetic",(4,4),"Regional network path restored by the transmission team; no platform fault.","",0,"Probe city lost its network path; only synthetic sessions affected."),
 ("T14",3,"Billing DB server disk full",["disk_full"],["svc-recharge"],"billing-db-01",(2,4),"DB Ops extended the filesystem by change request.","",0,"Billing data growth reached the filesystem limit."),
 ("T15",2,"USSD gateway timeouts on one node",["ussd_gateway_timeout"],["svc-balance-check","svc-bundle-purchase"],"ussd-gateway",(6,12),"VAS Operations restarted the affected gateway node.","",0,"One gateway node stopped answering after a configuration reload."),
]
STORM=("T01",1,"Subscriber DB /data full — archived transaction logs not purged",
 ["disk_full","db_write_errors","ussd_gateway_timeout","balance_api_errors","bundle_api_errors","sms_queue_growth","synthetic_ussd_failed"],
 ["svc-balance-check","svc-bundle-purchase","svc-sms-notify"],"db-core-01",(131,131),
 "Purged archived transaction logs older than 24 hours with the registered action; the stalled nightly purge job was restarted.","purge-archived-txlogs",4,
 "Nightly txlog purge stalled; /data reached 100%, DB writes failed, APIs and USSD sessions failed, SMS confirmations queued.")
recs=[]
for t in T:
    for i in range(t[1]): recs.append(t)
random.shuffle(recs)
recs.insert(41, STORM)            # 42nd chronologically -> PI-0342
assert len(recs)==50
# opened offsets: spread over 30 days before seed import, strictly increasing
mins=sorted(random.sample(range(60, 30*24*60), 50), reverse=True)   # minutes before import
rows=[]
for i,(t,offset) in enumerate(zip(recs,mins)):
    key=f"PI-{301+i:04d}"
    ack=random.randint(2,7) if t[0]!="T09" else random.randint(5,9)
    rec=random.randint(9,45)
    if t[0]=="T01": ack,rec=4,22
    ac=random.randint(*t[6])
    rows.append({"past_incident_key":key,"title":t[2],"record_label":"Fictional record",
      "opened_minutes_before_import":offset,"acknowledged_after_minutes":ack,"recovered_after_minutes":rec,
      "alert_count":ac,"alert_types":";".join(sorted(t[3])),"affected_service_keys":";".join(sorted(t[4])),
      "hypothesis_component_key":t[5],"summary":t[10],"fix_description":t[7],"fix_action_key":t[8],
      "fix_success_count":(t[9] if t[9]>0 else random.randint(1,5))})
with open(sys.argv[1],"w",newline="") as f:
    w=csv.DictWriter(f,fieldnames=list(rows[0].keys())); w.writeheader(); w.writerows(rows)
print("written", len(rows))
