#!/bin/bash
##############################################################################
# 故障转移演练：杀掉主库，观察哨兵是否把从库提升为新主
#   --dry-run 只打印将要执行的步骤
#   不带参数   真正执行（会 kill 6379 的 redis 进程，再让原主库以从库身份回归）
##############################################################################
set -uo pipefail
MASTER_PORT=6379
REPLICA_PORT=6380
MASTER_NAME="llmall-master"
PW="123456"
SENTINEL_PORTS=(26379 26380 26381)
DRY=""
[[ "${1:-}" == "--dry-run" ]] && DRY=1

r() { redis-cli -h 127.0.0.1 -p "$1" -a "$PW" --no-auth-warning "${@:2}" 2>/dev/null; }
s() { redis-cli -h 127.0.0.1 -p "$1" "${@:2}" 2>/dev/null; }
role_of() { r "$1" INFO replication | grep '^role:' | tr -d '\r' | cut -d: -f2; }
master_from_sentinel() { s "${SENTINEL_PORTS[0]}" SENTINEL get-master-addr-by-name "$MASTER_NAME" | paste -sd: -; }

echo "==================== 演练前状态 ===================="
echo "6379 角色: $(role_of $MASTER_PORT)"
echo "6380 角色: $(role_of $REPLICA_PORT)"
echo "哨兵认为主库: $(master_from_sentinel)"

# 【前置条件：复制链路必须 up，且从库数据要跟得上】
# 这是实测踩到的坑：上一次修复刚把从库指回主库，master_link_status 还是 down，
# 就直接开始演练 —— 哨兵虽然成功选出领导者、也下发了 SLAVEOF NO ONE，
# 但它选中的是一个"复制链路还没建立"的从库，提升迟迟不完成，
# 卡在 +failover-state-wait-promotion，最后被原主库重启打断。
# 看起来像"哨兵坏了"，实际是**演练时机不对**。演练前必须等到链路就绪。
echo -n "等待复制链路就绪"
LINK=""
for i in $(seq 1 40); do
  LINK="$(r $REPLICA_PORT INFO replication | grep '^master_link_status:' | tr -d '\r' | cut -d: -f2)"
  if [[ "$LINK" == "up" ]]; then echo " -> up (${i}s)"; break; fi
  echo -n "."
  sleep 1
done
if [[ "$LINK" != "up" ]]; then
  echo
  echo "[FAIL] 从库复制链路未就绪（master_link_status=$LINK），此时演练结果不可信。"
  echo "       请先排查：redis-cli -p 6380 -a $PW --no-auth-warning INFO replication"
  exit 2
fi
# 主从偏移量差距也要收窄，否则切换后可能少几条数据，容易误判成"切换导致丢数据"
for i in $(seq 1 20); do
  M_OFF="$(r $MASTER_PORT INFO replication | grep '^master_repl_offset:' | tr -d '\r' | cut -d: -f2)"
  R_OFF="$(r $REPLICA_PORT INFO replication | grep '^slave_repl_offset:' | tr -d '\r' | cut -d: -f2)"
  DIFF=$(( ${M_OFF:-0} - ${R_OFF:-0} ))
  [[ "$DIFF" -le 0 ]] && break
  sleep 1
done
echo "复制偏移差: 主=$M_OFF 从=$R_OFF 差=$DIFF（期望接近 0）"
echo "从库复制到的数据量: $(r $REPLICA_PORT DBSIZE)"

if [[ -n "$DRY" ]]; then
  echo
  echo "[dry-run] 将执行："
  echo "  1. kill 6379 的 redis 进程（模拟主库宕机）"
  echo "  2. 等待哨兵判定客观下线并执行故障转移"
  echo "  3. 校验 6380 被提升为 master、哨兵下发的地址变成 6380"
  echo "  4. 让原 6379 以**从库**身份回归（SENTINEL FAILOVER 或手工 replicaof）"
  exit 0
fi

echo
echo "==================== 1. 停掉主库 6379 ===================="
# 【为什么用 systemctl stop 而不是 kill -9】主库由 systemd 托管且配了 Restart=always，
# kill 掉之后 systemd 会在几秒内把它拉起来，而哨兵的 down-after-milliseconds=3000
# 可能还没判定完主库就"活了"——那样演练根本没进入故障转移，却会打印出一堆看似正常的输出，
# 是最容易自欺欺人的一种"通过"。用 stop 才能制造一段**持续的**宕机窗口。
# 记下 run_id：重启后它会变，用来确认"确实是同一个实例回来了"。
OLD_RUN_ID="$(r $MASTER_PORT INFO server | grep '^run_id:' | tr -d '\r' | cut -d: -f2)"
echo "主库 run_id=$OLD_RUN_ID"
systemctl stop redis-server
sleep 1
echo "6379 是否还在监听: $(ss -lnt | grep -c ':6379 ')"

echo
echo "==================== 2. 等待哨兵故障转移 ===================="
# 【等待窗口必须足够长 —— 实测教训】第一版只等 40 秒就判定"哨兵没切换"，
# 而哨兵日志显示切换实际发生在 t+47s 左右（+switch-master 落在 failover-timeout 内的重试节奏上）。
# 于是脚本报了一堆 FAIL，我据此误判"哨兵配置有问题"，反复去修一个**本来就正常**的集群。
# 结论：等待窗口要覆盖 failover-timeout(180s) 而非拍脑袋取 40s。
# 另外注意：**不要连续快速演练**。哨兵每次尝试失败会进入 failover-timeout×2 的冷却
# （日志里的 "Next failover delay: I will not start a failover before ..."），
# 冷却期内主库再挂也不会切换 —— 连续演练时看到的"完全没反应"多半是这个原因。
# 若确实需要连做多次，先重启三个哨兵清掉冷却与纪元。
NEW_MASTER=""
SWITCH_AT=""
for i in $(seq 1 200); do
  sleep 1
  NEW_MASTER="$(master_from_sentinel)"
  if (( i % 5 == 0 )); then
    echo "  t+${i}s  哨兵认为主库=${NEW_MASTER}  6380角色=$(role_of $REPLICA_PORT)"
  fi
  if [[ "$NEW_MASTER" == "192.168.189.128:${REPLICA_PORT}" ]]; then
    SWITCH_AT="$i"
    echo "  t+${i}s  → 故障转移完成（+switch-master 已生效）"
    break
  fi
done
echo "  切换耗时: ${SWITCH_AT:-未完成}s（哨兵 down-after=3s + 选举 + wait-promotion，通常 10~60s）"

echo
echo "==================== 3. 校验 ===================="
FAIL=0
[[ "$NEW_MASTER" == "192.168.189.128:${REPLICA_PORT}" ]] && echo "[PASS] 哨兵已把主库切到 6380" || { echo "[FAIL] 哨兵未切换，仍是 $NEW_MASTER"; FAIL=1; }
[[ "$(role_of $REPLICA_PORT)" == "master" ]] && echo "[PASS] 6380 角色=master" || { echo "[FAIL] 6380 角色=$(role_of $REPLICA_PORT)"; FAIL=1; }
# 新主库必须可写（故障转移后它必须脱离只读）
r $REPLICA_PORT SET __dsh_failover_probe__ ok >/dev/null
[[ "$(r $REPLICA_PORT GET __dsh_failover_probe__)" == "ok" ]] && echo "[PASS] 新主库可写（只读已解除）" || { echo "[FAIL] 新主库不可写"; FAIL=1; }
r $REPLICA_PORT DEL __dsh_failover_probe__ >/dev/null
# 三个哨兵必须一致
for p in "${SENTINEL_PORTS[@]}"; do
  v="$(s $p SENTINEL get-master-addr-by-name "$MASTER_NAME" | paste -sd: -)"
  [[ "$v" == "192.168.189.128:${REPLICA_PORT}" ]] && echo "[PASS] 哨兵 $p 口径一致" || { echo "[FAIL] 哨兵 $p 认为主库=$v"; FAIL=1; }
done
# 数据必须还在（异步复制下，切换前已同步的数据不能丢）
N="$(r $REPLICA_PORT DBSIZE)"
[[ "$N" -ge 80 ]] && echo "[PASS] 切换后新主库数据量=$N（未丢失）" || { echo "[FAIL] 新主库数据量仅 $N，疑似丢数据"; FAIL=1; }

echo
echo "==================== 4. 让原 6379 回归 ===================="
echo "拉起原主库（systemctl start redis-server），此时它仍会以 master 身份自居（配置里没有 replicaof），"
echo "所以必须显式执行 REPLICAOF 让它变成新主的从库 —— 这一步是**人工/脚本必须补的**，"
echo "否则会形成两个各自为政的 master（双主），后续写入会分叉。"
systemctl start redis-server
for i in $(seq 1 20); do
  ss -lnt | grep -q ':6379 ' && break
  sleep 1
done
if ss -lnt | grep -q ':6379 '; then
  echo "6379 已重新监听，run_id=$(r $MASTER_PORT INFO server | grep '^run_id:' | tr -d '\r' | cut -d: -f2)"
  echo "执行 REPLICAOF 192.168.189.128 6380"
  r $MASTER_PORT REPLICAOF 192.168.189.128 $REPLICA_PORT
  sleep 4
  echo "6379 角色现在是: $(role_of $MASTER_PORT)"
  echo "6380 connected_slaves: $(r $REPLICA_PORT INFO replication | grep '^connected_slaves' | tr -d '\r')"
  [[ "$(role_of $MASTER_PORT)" == "slave" ]] && echo "[PASS] 原主库已作为从库回归，恢复成一主一从" || echo "[WARN] 6379 角色仍为 $(role_of $MASTER_PORT)，需人工确认"
  # 顺便验证：回归后的从库确实在同步新主的数据（写一个新主键，看从库能否读到）
  r $REPLICA_PORT SET __dsh_repl_probe__ sync_ok >/dev/null
  sleep 2
  [[ "$(r $MASTER_PORT GET __dsh_repl_probe__)" == "sync_ok" ]] \
    && echo "[PASS] 新主写入已同步到回归后的从库" \
    || echo "[WARN] 同步验证未通过（可能是复制还在建立，稍后重查）"
  r $REPLICA_PORT DEL __dsh_repl_probe__ >/dev/null
else
  echo "[WARN] 6379 未启动，请手工 systemctl start redis-server"
fi

echo "==================== 5. 收尾：把拓扑复位成 6379 主 / 6380 从 ===================="
# 【为什么演练必须自带复位】前面的演练每跑一次都会把拓扑改成"6380 主 / 6379 从"，
# 而 6379 的配置文件里没有 replicaof、6380 的配置里写死了 replicaof 6379，
# 于是重启后会各自恢复成"一个自认 master、一个自认 slave"，看起来正常，
# 但如果连续演练几次、中间又手工 REPLICAOF 过，很容易形成**环形复制**
# （6379 是从、6380 也是从，且互为对方的主）—— 两边都显示 master_link_status:up，
# 数据却可能在两边分叉，这种状态最容易被误判成"健康"。所以复位必须由脚本自己做死。
r $REPLICA_PORT REPLICAOF NO ONE >/dev/null 2>&1
sleep 1
r $MASTER_PORT REPLICAOF 127.0.0.1 $REPLICA_PORT >/dev/null 2>&1
echo -n "等待 6379 跟随 6380 的链路建立"
for i in $(seq 1 30); do
  [[ "$(r $MASTER_PORT INFO replication | grep '^master_link_status:' | tr -d '\r' | cut -d: -f2)" == "up" ]] && { echo " -> up (${i}s)"; break; }
  echo -n "."
  sleep 1
done
echo "  过渡态：6380=master, 6379=slave（数据仍在 6380 上）"

echo "现在切回 6379 主 / 6380 从（本项目 6379 是长期主库，配置与文档都以它为准）"
r $MASTER_PORT REPLICAOF NO ONE >/dev/null 2>&1
sleep 1
r $REPLICA_PORT REPLICAOF 127.0.0.1 $MASTER_PORT >/dev/null 2>&1
echo -n "等待 6380 重新同步完成"
for i in $(seq 1 30); do
  [[ "$(r $REPLICA_PORT INFO replication | grep '^master_link_status:' | tr -d '\r' | cut -d: -f2)" == "up" ]] && { echo " -> up (${i}s)"; break; }
  echo -n "."
  sleep 1
done

# 复位后必须校验：两个都是 slave、或者环形复制，都算复位失败
R6379="$(role_of $MASTER_PORT)"
R6380="$(role_of $REPLICA_PORT)"
echo "  复位结果：6379=$R6379  6380=$R6380"
if [[ "$R6379" == "master" && "$R6380" == "slave" ]]; then
  echo "[PASS] 拓扑已复位为一主一从（6379 主 / 6380 从）"
else
  echo "[FAIL] 拓扑复位异常（6379=$R6379, 6380=$R6380），可能形成环形复制，请人工介入"
  FAIL=$((FAIL+1))
fi

# 【关键：复位拓扑后必须重启哨兵】
# 哨兵在演练中已经学到"主库是 6380"，而这个认知是持久的（写在配置文件里）。
# 我们手工把拓扑改回 6379 主之后，哨兵仍会认为自己该管的主库是 6380 ——
# 表现为复位明明成功（6379=master/6380=slave），`SENTINEL get-master-addr-by-name`
# 却仍返回 6380，看起来像"复位没做干净"。
# 重启哨兵会让它按配置文件里的 monitor 行重新发现拓扑，同时也顺手清掉 failover 纪元与冷却。
echo "重启哨兵，让它们按 monitor 行重新认识当前拓扑（并清掉演练期间累积的 epoch 与冷却）"
for p in 26379 26380 26381; do systemctl restart "redis-sentinel@$p"; done
sleep 8
for i in $(seq 1 20); do
  [[ "$(master_from_sentinel)" == "192.168.189.128:${MASTER_PORT}" ]] && break
  sleep 1
done
echo "  哨兵认为主库：$(master_from_sentinel)"
[[ "$(master_from_sentinel)" == "192.168.189.128:${MASTER_PORT}" ]] \
  && echo "[PASS] 哨兵口径已回到 6379" \
  || { echo "[FAIL] 哨兵仍认为主库是 $(master_from_sentinel)"; FAIL=$((FAIL+1)); }

echo
echo "==================== 演练结束 ===================="
echo "6379 角色: $(role_of $MASTER_PORT)（数据 $(r $MASTER_PORT DBSIZE)）"
echo "6380 角色: $(role_of $REPLICA_PORT)（数据 $(r $REPLICA_PORT DBSIZE)）"
echo "哨兵认为主库: $(master_from_sentinel)"
echo
if [[ "$FAIL" -eq 0 ]]; then echo "结论：故障转移演练通过，拓扑已复位"; else echo "结论：有 $FAIL 项失败，需人工排查"; fi
exit $FAIL
