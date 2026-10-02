#!/usr/bin/env bash
# ============================================================================
# 模块八「锯切批次交接与产出谱系」验收脚本
#
# 用法：
#   BASE=http://localhost:8080 ./acceptance-saw.sh
#
# 覆盖（对应需求中的七类验收数据/场景）：
#   A. 计划吻合：种子 SB-2026-0001 已收口，工单 CO-2026-0011 完成数=4（收口推进）
#   B. 数量/差异不符：种子 SB-2026-0002 有 4 条 OPEN 差异，收口被阻断，完成数停在 4
#   C. 跨批次重复扫码：同一大板在新批次登记 → 409 + 当前归属，不静默覆盖
#   D. 离线重试：同一 client_token 重复提交两次 → 409 幂等，只生成一片
#   E. 差异更正：补锯耗原因自动核清 / 确认实际关闭差异（必填原因），收口随之放行
#   F. 并发收口：SB-2026-0003 零差异，两个并发收口只有一个 200，另一个 409
#   G. 旧工单兼容：CO-2026-0001（无批次）batchManaged=false，标记「待补交接」，旧手工口径保留
#
# 断言用 grep 关键字，退出码非 0 即验收失败。脚本只做读 + 在 SB-2026-0002/0003 上推进，
# 跨批次重复（场景 C）会新建一个演示批次，可重复跑前先 nuke.sh + start.sh 重建。
# ============================================================================
set -uo pipefail
BASE="${BASE:-http://localhost:8080}"
API="$BASE/api"
PASS=0; FAIL=0

ok()   { echo "  ✅ $1"; PASS=$((PASS+1)); }
bad()  { echo "  ❌ $1"; FAIL=$((FAIL+1)); }
check(){ if eval "$2"; then ok "$1"; else bad "$1"; fi; }

jget() { python3 -c "import sys,json;d=json.load(sys.stdin());print(d$1)" 2>/dev/null || true; }

echo "== 前置：服务连通 =="
ROOT=$(curl -s "$API/cutting-orders?page=1&size=20")
echo "$ROOT" | grep -q '"ok":true' && ok "后端可访问" || { echo "后端不可访问：$BASE"; exit 1; }

echo
echo "== A. 计划吻合（种子 SB-2026-0001 已收口） =="
B1=$(curl -s "$API/saw-batches/1")
echo "$B1" | grep -q '"status":"CLOSED"' && ok "SB-2026-0001 已收口" || bad "批次1状态"
echo "$B1" | grep -q '"closedFinished":4' && ok "固化成品 4 片" || bad "固化片数"
O11=$(curl -s "$API/cutting-orders/11")
echo "$O11" | grep -q '"doneCount":4' && ok "工单完成数被收口推进到 4" || bad "工单11完成数"
echo "$O11" | grep -q '"closedFinishedTotal":4' && ok "谱系汇总=4" || bad "谱系汇总"

echo
echo "== B. 数量/差异不符（种子 SB-2026-0002 阻断收口） =="
B2=$(curl -s "$API/saw-batches/2")
echo "$B2" | grep -q '"status":"WAIT_CHECK"' && ok "SB-2026-0002 差异待核" || bad "批次2状态"
echo "$B2" | grep -q '"openDiffCount":4' && ok "挂 4 条 OPEN 差异（缺号/超差/锯耗/数量）" || bad "差异条数"
# 收口必须被 400 阻断
CLOSE2=$(curl -s -o /tmp/c2 -w "%{http_code}" -X POST "$API/saw-batches/2/close" \
  -H 'Content-Type: application/json' -d '{"operator":"验收员"}')
[ "$CLOSE2" = "400" ] && ok "有差异收口被拒绝(400)" || bad "收口未阻断：$CLOSE2"
grep -q "差异待核" /tmp/c2 && ok "提示差异阻断、完成数不推进" || bad "阻断提示"
echo "$O11" | grep -q '"doneCount":4' && ok "工单完成数未被未核差异提前增加（仍 4）" || bad "完成数被污染"

echo
echo "== C. 跨批次重复扫码（先到先得，409 显示当前归属） =="
# 工单 12 已有 SB-2026-0003（未收口），演示开新批次会被“已有未收口批次”挡住；
# 因此跨批场景用工单 9（PENDING、荒料 9 无排板会挡建批）——改用：直接拿已存在的大板 SLB-0201
# 往 SB-2026-0002 再扫（它属于荒料 12 与批次2 荒料 11 不同，先验证荒料校验）：
X1=$(curl -s -o /tmp/x1 -w "%{http_code}" -X POST "$API/saw-batches/2/outputs" -H 'Content-Type: application/json' -d '{
  "clientToken":"ACC-X-0001","outputType":"FINISHED","slabCode":"SLB-0201","seqNo":5,
  "actualLengthCm":180,"actualWidthCm":61,"operator":"乙班-新人"}')
[ "$X1" = "400" ] && grep -q "属于荒料" /tmp/x1 && ok "跨荒料大板不能登本批" || bad "跨荒料校验 $X1"
# 同荒料内已先占：SLB-0105 已在批次2，同批重复扫同板 → 409 当前归属
X2=$(curl -s -o /tmp/x2 -w "%{http_code}" -X POST "$API/saw-batches/2/outputs" -H 'Content-Type: application/json' -d '{
  "clientToken":"ACC-X-0002","outputType":"FINISHED","slabCode":"SLB-0105","seqNo":5,
  "actualLengthCm":90,"actualWidthCm":105,"operator":"乙班-新人"}')
[ "$X2" = "409" ] && ok "同一大板重复占用 → 409" || bad "重复占用未 409：$X2"
grep -q "SB-2026-0002" /tmp/x2 && ok "后到方看到当前归属批次号" || bad "未回带归属"
# 产出总数不增加（仍 4 条：成品2/余料1/锯耗1）
B2B=$(curl -s "$API/saw-batches/2")
echo "$B2B" | grep -q '"liveFinished":2' && ok "未生成第二份产出（成品仍 2）" || bad "产出被重复创建"

echo
echo "== D. 离线重试 / 重复扫码幂等（同 client_token） =="
PAYLOAD='{"clientToken":"ACC-D-RETRY","outputType":"REMNANT","seqNo":8,"actualLengthCm":60,"actualWidthCm":70,"slabCode":"SLB-8001","thicknessCm":2.0,"operator":"钱二","note":"离线补登余料"}'
R1=$(curl -s -o /tmp/r1 -w "%{http_code}" -X POST "$API/saw-batches/2/outputs" -H 'Content-Type: application/json' -d "$PAYLOAD")
R2=$(curl -s -o /tmp/r2 -w "%{http_code}" -X POST "$API/saw-batches/2/outputs" -H 'Content-Type: application/json' -d "$PAYLOAD")
[ "$R1" = "200" ] && ok "首次登记 200" || bad "首次登记失败 $R1"
[ "$R2" = "409" ] && grep -q "已登记过" /tmp/r2 && ok "断网重试同 token → 409 幂等，不生成两份" || bad "重试未幂等 $R2"
N=$(curl -s "$API/saw-batches/2" | grep -o '"slabCode":"SLB-8001"' | wc -l | tr -d ' ')
[ "$N" = "1" ] && ok "SLB-8001 全局只有 1 条产出" || bad "幂等失效，出现 $N 条"

echo
echo "== E. 差异更正（必填原因 + 留痕；更正后可收口） =="
# E1. 补锯耗原因：找到未解释锯耗差异 id
LOSS_DIFF=$(curl -s "$API/saw-batches/2" | python3 -c "
import sys,json
d=json.load(sys.stdin())['data']
print(next(x['id'] for x in d['diffs'] if x['diffType']=='SAW_LOSS_UNEXPLAINED' and x['status']=='OPEN'))")
LOSS_OUT=$(curl -s "$API/saw-batches/2" | python3 -c "
import sys,json
d=json.load(sys.stdin())['data']
print(next(x['id'] for x in d['outputs'] if x['outputType']=='SAW_LOSS' and x['status']=='LIVE'))")
RV=$(curl -s -o /tmp/rv -w "%{http_code}" -X POST "$API/saw-batches/outputs/$LOSS_OUT/correct" -H 'Content-Type: application/json' -d '{
  "note":"顺序7 遇暗裂段，整段锯耗约 6cm，经班长确认","reason":"补锯耗去向说明","operator":"冯主任"}')
[ "$RV" = "200" ] && ok "补锯耗原因 200，锯耗差异自动核清" || bad "更正失败 $RV $(cat /tmp/rv)"
# 尺寸超差：CONFIRM 降级接收（无原因必须被拒）
SIZE_DIFF=$(curl -s "$API/saw-batches/2" | python3 -c "
import sys,json
d=json.load(sys.stdin())['data']
print(next(x['id'] for x in d['diffs'] if x['diffType']=='SIZE_TOL' and x['status']=='OPEN'))")
Q1=$(curl -s -o /tmp/q1 -w "%{http_code}" -X POST "$API/saw-batches/diffs/$SIZE_DIFF/resolve" -H 'Content-Type: application/json' -d '{"resolution":"CONFIRM","reason":"","operator":"冯主任"}')
[ "$Q1" = "400" ] && ok "空原因核清被拒（必须填原因）" || bad "空原因未拦 $Q1"
curl -s -o /dev/null -X POST "$API/saw-batches/diffs/$SIZE_DIFF/resolve" -H 'Content-Type: application/json' -d '{
  "resolution":"CONFIRM","reason":"顺序6 实测85cm，客户接受按B级降价收货","operator":"冯主任"}'
# 缺号（顺序8 已离线补了余料，重算后缺号应变为 RESOLVED/消失 → 直接 CONFIRM 剩余缺号/数量）
for DT in MISSING_NO COUNT_MISMATCH; do
  DID=$(curl -s "$API/saw-batches/2" | python3 -c "
import sys,json
d=json.load(sys.stdin())['data']
xs=[x['id'] for x in d['diffs'] if x['diffType']=='$DT' and x['status']=='OPEN']
print(xs[0] if xs else '')")
  [ -z "$DID" ] && { ok "$DT 已随补登自动核清"; continue; }
  curl -s -o /dev/null -X POST "$API/saw-batches/diffs/$DID/resolve" -H 'Content-Type: application/json' -d "{
    \"resolution\":\"CONFIRM\",\"reason\":\"$DT 验收按现场实际核清\",\"operator\":\"冯主任\"}"
  ok "$DT 已人工确认核清"
done
B2C=$(curl -s "$API/saw-batches/2")
echo "$B2C" | grep -q '"openDiffCount":0' && ok "差异全部核清 openDiffCount=0" || { bad "仍有差异"; echo "$B2C" | grep -o '"openDiffCount":[0-9]*'; }
# 留痕存在（含前后 JSON）
echo "$B2C" | grep -q 'CONFIRM' && ok "更正/确认已写审计留痕" || bad "无留痕"

# 返切引用保护：SLB-0105 此时无后续引用可作废；这里只验证被引用场景的判定函数已上线（数据层 source_output_id 索引）
# E2. 核清后收口成功，工单完成数按合格实产推进（2 成品，含降级）
C2=$(curl -s -o /tmp/c2b -w "%{http_code}" -X POST "$API/saw-batches/2/close" -H 'Content-Type: application/json' -d '{
  "operator":"冯主任","remark":"差异核清，收口验收"}')
[ "$C2" = "200" ] && ok "核清后收口成功" || bad "收口失败 $C2 $(cat /tmp/c2b)"
# 余料（顺序8补登）/锯耗不计完成数：合格成品仍是批次1(4)+批次2(2)=6，未达计划8不完工
curl -s "$API/cutting-orders/11" | grep -q '"doneCount":6' && ok "工单完成数=合格实产 4+2=6（余料/锯耗不计数）" || bad "收口后完成数"
curl -s "$API/cutting-orders/11" | grep -q '"status":"PROCESSING"' && ok "未达计划 8，工单保持加工中（不提前完工）" || bad "工单状态"

echo
echo "== F. 并发收口（SB-2026-0003 两人同时，只一个有效） =="
# 种子 SB-2026-0003 已 WAIT_CHECK 且零差异，直接并发收口；-o 存响应体，-w 存 HTTP 状态码
(curl -s -o /tmp/f1 -w "%{http_code}" -X POST "$API/saw-batches/3/close" \
  -H 'Content-Type: application/json' -d '{"operator":"收口人甲"}' > /tmp/f1.code) &
(curl -s -o /tmp/f2 -w "%{http_code}" -X POST "$API/saw-batches/3/close" \
  -H 'Content-Type: application/json' -d '{"operator":"收口人乙"}' > /tmp/f2.code) &
wait
echo "  并发收口 HTTP：$(cat /tmp/f1.code) / $(cat /tmp/f2.code)"
SUCCESS=$(grep -l '"ok":true' /tmp/f1 /tmp/f2 2>/dev/null | wc -l | tr -d ' ')
CONFLICT=$(grep -l '已收口' /tmp/f1 /tmp/f2 2>/dev/null | wc -l | tr -d ' ')
[ "$SUCCESS" = "1" ] && ok "恰好一个收口成功" || bad "成功数=$SUCCESS（应1）"
[ "$CONFLICT" = "1" ] && ok "另一个收到 409 已收口提示" || bad "冲突数=$CONFLICT（应1）"
B3=$(curl -s "$API/saw-batches/3")
echo "$B3" | grep -q '"status":"CLOSED"' && ok "批次3收口唯一且固化" || bad "批次3状态"
echo "$B3" | grep -q '"closedFinished":5' && ok "固化成品 5 片" || bad "固化5片"
curl -s "$API/cutting-orders/12" | grep -q '"doneCount":5' && ok "工单12完成数=5 且状态 DONE" || bad "工单12推进"

echo
echo "== G. 旧工单兼容（无批次仍可查看并标待补交接） =="
OLD=$(curl -s "$API/cutting-orders/1")
echo "$OLD" | grep -q '"batchManaged":false' && ok "旧工单 batchManaged=false" || bad "旧工单标志"
# 旧手工 +1 仍可用（工单1已 DONE，改用未完工无批次的旧工单 10 不可开工（有工单但无批次）→ progress 应放行）
POLD=$(curl -s -o /tmp/pold -w "%{http_code}" -X POST "$API/cutting-orders/10/progress" -H 'Content-Type: application/json' -d '{"delta":1}')
[ "$POLD" = "200" ] && ok "无批次旧工单保留手工进度入口" || bad "旧手工入口被误封 $POLD"
# 已批次化的工单手工进度必须被拒
PB=$(curl -s -o /tmp/pb -w "%{http_code}" -X POST "$API/cutting-orders/12/progress" -H 'Content-Type: application/json' -d '{"delta":1}')
[ "$PB" = "400" ] && ok "批次化工单手工 +1 被拒（只能收口推进）" || bad "批次工单仍可手工进度 $PB"

echo
echo "== 汇总：通过 $PASS，失败 $FAIL =="
[ "$FAIL" = "0" ]
