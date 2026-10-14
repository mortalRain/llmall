#!/bin/bash
##############################################################################
# 最小复现实验集群（与现役 6379/6380 + 26379-26381 完全隔离，互不影响）
#
#   主 127.0.0.1:7001   从 127.0.0.1:7002   哨兵 127.0.0.1:27001/27002/27003
#   数据目录 /var/lib/redis-lab，日志 /var/log/redis-lab
#
# 目的：把"故障转移卡在 wait-promotion"这件事在**最简配置**下复现或排除。
# 最简 = 不带 rename-command、不带 replica-announce-*、单实例目录、无 AOF/RDB 调优。
#
# 用法：bash lab-cluster.sh {up|down|status|promote-test|failover-test|add-configs}
##############################################################################
set -uo pipefail
PW=123456
IP=192.168.189.128
MP=7001      # master port
RP=7002      # replica port
SPORTS=(27001 27002 27003)
BASE=/var/lib/redis-lab
LOGD=/var/log/redis-lab
RUN=/run/redis-lab
STATE=basic           # basic = 最简；full = 加上现役集群用到的那些配置项

r() { redis-cli -h 127.0.0.1 -p "$1" -a "$PW" --no-auth-warning "${@:2}" 2>/dev/null; }
s() { redis-cli -h 127.0.0.1 -p "$1" "${@:2}" 2>/dev/null; }
role_of() { r "$1" INFO replication | grep '^role:' | tr -d '\r' | cut -d: -f2; }
m() { s "${SPORTS[0]}" SENTINEL get-master-addr-by-name lab-master | paste -sd: -; }

mk_dirs() {
  mkdir -p "$BASE/$MP" "$BASE/$RP" "$LOGD" "$RUN"
  for p in "${SPORTS[@]}"; do mkdir -p "$RUN/$p"; done
  chown -R redis:redis "$BASE" "$LOGD" "$RUN"
  chmod 750 "$BASE" "$LOGD"
}

# 额外配置：现役集群里用到、但"最简"版本没有的项，用于二分定位
extra_conf() {
  local role="$1"
  # rename-command 分组定位：现役集群为"加固"做了四条 rename，
  # 二分已确认**就是这一组导致故障转移失效**（只加 replica-announce 时 t+5s 切换成功）。
  # 现在逐条试，找出具体是哪一条 —— 每条单测一次，避免组合掩盖。
  case "$STATE" in
    ren-config)   echo 'rename-command CONFIG "CONFIG_9f3a1c"' ;;
    ren-noconfig)
      echo 'rename-command FLUSHALL ""'
      echo 'rename-command FLUSHDB ""'
      echo 'rename-command SHUTDOWN "SHUTDOWN_9f3a1c"'
      if [[ "$role" == "replica" ]]; then echo "replica-announce-ip $IP"; echo "replica-announce-port $RP"; fi ;;
    ren-shutdown) echo 'rename-command SHUTDOWN "SHUTDOWN_9f3a1c"' ;;
    ren-flush)    echo 'rename-command FLUSHALL ""'
                  echo 'rename-command FLUSHDB ""' ;;
    rename)
      echo 'rename-command FLUSHALL ""'
      echo 'rename-command FLUSHDB ""'
      echo 'rename-command CONFIG "CONFIG_9f3a1c"'
      echo 'rename-command SHUTDOWN "SHUTDOWN_9f3a1c"' ;;
    full)
      echo 'rename-command FLUSHALL ""'
      echo 'rename-command FLUSHDB ""'
      echo 'rename-command CONFIG "CONFIG_9f3a1c"'
      echo 'rename-command SHUTDOWN "SHUTDOWN_9f3a1c"'
      if [[ "$role" == "replica" ]]; then
        echo "replica-announce-ip $IP"
        echo "replica-announce-port $RP"
      fi ;;
  esac
}

# 哨兵侧的可变量：主要用来验证 failover-timeout 的影响
sentinel_extra() {
  if [[ "$STATE" == "full" ]]; then
    echo "sentinel failover-timeout lab-master 180000"
  else
    echo "sentinel failover-timeout lab-master 30000"
  fi
}

write_node_conf() {
  local port="$1"
  local role="$2"
  # 【注意】不要写成 `local port="$1" role="$2" conf="$BASE/$port/..."`：
  # local 的多重赋值会**先展开所有右侧表达式**，此时 port 尚未成为局部变量，
  # 在 set -u 下直接报 "port: unbound variable"（实测踩过）。
  local conf="$BASE/$port/redis.conf"
  {
    echo "port $port"
    echo "bind 0.0.0.0"
    echo "protected-mode no"
    echo "daemonize no"
    echo "supervised systemd"
    echo "pidfile $RUN/$port.pid"
    echo "logfile $LOGD/$port.log"
    echo "dir $BASE/$port"
    echo "dbfilename dump.rdb"
    echo "save 900 1"
    echo "appendonly no"
    echo "maxmemory 64mb"
    echo "maxmemory-policy noeviction"
    echo "requirepass $PW"
    echo "masterauth $PW"
    echo "replica-read-only yes"
    if [[ "$role" == "replica" ]]; then
      echo "replicaof $IP $MP"
    fi
    extra_conf "$role"
  } > "$conf"
  chown redis:redis "$conf"; chmod 640 "$conf"
}

write_sentinel_conf() {
  local sport="$1"
  local conf="$BASE/sentinel-$sport.conf"
  {
    echo "port $sport"
    echo "bind 0.0.0.0"
    echo "protected-mode no"
    echo "daemonize no"
    echo "supervised systemd"
    echo "pidfile $RUN/$sport.pid"
    echo "logfile $LOGD/sentinel-$sport.log"
    echo "dir $RUN/$sport"
    echo "sentinel monitor lab-master $IP $MP 1"
    echo "sentinel auth-pass lab-master $PW"
    echo "sentinel down-after-milliseconds lab-master 3000"
    sentinel_extra
    echo "sentinel parallel-syncs lab-master 1"
  } > "$conf"
  chown redis:redis "$conf"; chmod 640 "$conf"
}

write_units() {
  cat > /etc/systemd/system/redis-lab-node@.service <<EOF
[Unit]
Description=Redis lab node %i
After=network.target
[Service]
Type=notify
User=redis
Group=redis
Environment="TZ=Asia/Shanghai"
ExecStart=/usr/bin/redis-server $BASE/%i/redis.conf
Restart=no
LimitNOFILE=65535
[Install]
WantedBy=multi-user.target
EOF
  cat > /etc/systemd/system/redis-lab-sentinel@.service <<EOF
[Unit]
Description=Redis lab sentinel %i
After=network.target
[Service]
Type=notify
User=redis
Group=redis
Environment="TZ=Asia/Shanghai"
ExecStart=/usr/bin/redis-server $BASE/sentinel-%i.conf --sentinel
Restart=no
LimitNOFILE=65535
[Install]
WantedBy=multi-user.target
EOF
  systemctl daemon-reload
}

do_up() {
  echo "===== 起最小集群（STATE=$STATE）====="
  mk_dirs
  write_node_conf "$MP" master
  write_node_conf "$RP" replica
  for p in "${SPORTS[@]}"; do write_sentinel_conf "$p"; done
  write_units
  systemctl stop redis-lab-node@$MP redis-lab-node@$RP 2>/dev/null
  for p in "${SPORTS[@]}"; do systemctl stop redis-lab-sentinel@$p 2>/dev/null; done
  sleep 1
  systemctl start redis-lab-node@$MP
  sleep 2
  systemctl start redis-lab-node@$RP
  echo -n "  等复制链路 up"
  for i in $(seq 1 30); do
    [[ "$(r $RP INFO replication | grep '^master_link_status:' | tr -d '\r' | cut -d: -f2)" == "up" ]] && { echo " (${i}s)"; break; }
    echo -n "."; sleep 1
  done
  for p in "${SPORTS[@]}"; do systemctl start redis-lab-sentinel@$p; done
  sleep 6
  do_status
}

do_down() {
  echo "===== 停最小集群 ====="
  for p in "${SPORTS[@]}"; do systemctl stop redis-lab-sentinel@$p 2>/dev/null; done
  systemctl stop redis-lab-node@$MP redis-lab-node@$RP 2>/dev/null
  rm -f /etc/systemd/system/redis-lab-node@.service /etc/systemd/system/redis-lab-sentinel@.service
  systemctl daemon-reload
  echo "  已停（数据保留在 $BASE，需要彻底清理可 rm -rf $BASE $LOGD $RUN）"
}

do_status() {
  echo "===== 最小集群状态 ====="
  echo "  $MP=$(role_of $MP)  $RP=$(role_of $RP)"
  echo "  $RP 链路=$(r $RP INFO replication | grep '^master_link_status' | tr -d '\r' | cut -d: -f2)"
  echo "  哨兵认为主库=$(m)"
  for p in "${SPORTS[@]}"; do
    local ns fl
    ns="$(s $p SENTINEL master lab-master | grep -A1 '^num-other-sentinels$' | tail -1)"
    fl="$(s $p SENTINEL master lab-master | grep -A1 '^flags$' | tail -1)"
    echo "  哨兵 $p: 其它哨兵=$ns flags=$fl"
  done
  echo "  fix-slave-config 累计=$(grep -c 'fix-slave-config' "$LOGD"/sentinel-*.log 2>/dev/null | awk -F: '{s+=$2} END {print s+0}')"
}

# 手工提升测试：证明从库本身能否被提升
promote_test() {
  echo "===== 手工提升测试（REPLICAOF NO ONE）====="
  echo "  提升前: $RP=$(role_of $RP)"
  local t0; t0=$(date +%s)
  r $RP REPLICAOF NO ONE >/dev/null
  sleep 2
  echo "  提升后: $RP=$(role_of $RP)  耗时 $(( $(date +%s) - t0 ))s"
  if [[ "$(role_of $RP)" == "master" ]]; then
    echo "  [结论] 从库可被瞬间手工提升 → 问题不在 Redis"
  else
    echo "  [结论] 从库连手工提升都失败 → 问题在从库自身"
  fi
  # 复原
  r $RP REPLICAOF $IP $MP >/dev/null
  for i in $(seq 1 20); do
    [[ "$(r $RP INFO replication | grep '^master_link_status:' | tr -d '\r' | cut -d: -f2)" == "up" ]] && break
    sleep 1
  done
  echo "  已复原: $MP=$(role_of $MP) $RP=$(role_of $RP)"
}

# 自动故障转移测试：停主库，等哨兵自己切
failover_test() {
  echo "===== 自动故障转移测试 ====="
  echo "  前置: $MP=$(role_of $MP) $RP=$(role_of $RP) 哨兵认为主库=$(m)"
  [[ "$(role_of $RP)" == "slave" ]] || { echo "  [中止] 从库不是 slave"; return 1; }
  echo "  停掉主库 $MP（systemctl stop，制造持续宕机）"
  systemctl stop redis-lab-node@$MP
  local t0; t0=$(date +%s); local ok=1
  # 等待窗口要覆盖 failover-timeout：STATE=full 时它是 180s，重试可能更久，所以给 240s
  local WAIT=240
  for i in $(seq 1 $WAIT); do
    sleep 1
    if [[ "$(m)" == "$IP:$RP" ]]; then
      echo "  [PASS] t+$(( $(date +%s) - t0 ))s 自动切换成功：主库=$(m)  $RP=$(role_of $RP)"
      ok=0; break
    fi
    (( i % 20 == 0 )) && echo "    t+${i}s 主库=$(m) $RP=$(role_of $RP)"
  done
  if [[ "$ok" == "1" ]]; then
    echo "  [FAIL] ${WAIT}s 内未自动切换"
    echo "  --- 哨兵 ${SPORTS[0]} 关键事件 ---"
    grep -E 'sdown master|odown master|try-failover|elected|failover-state|switch-master|Next failover' \
      "$LOGD/sentinel-${SPORTS[0]}.log" | tail -12
    echo "  --- 从库 $RP 日志尾部 ---"
    tail -8 "$LOGD/$RP.log"
  fi
  # 复原
  echo "  恢复主库..."
  systemctl start redis-lab-node@$MP
  sleep 2
  r $RP REPLICAOF NO ONE >/dev/null 2>&1; sleep 1
  r $MP REPLICAOF NO ONE >/dev/null 2>&1; sleep 1
  r $RP REPLICAOF $IP $MP >/dev/null 2>&1
  for i in $(seq 1 30); do
    [[ "$(r $RP INFO replication | grep '^master_link_status:' | tr -d '\r' | cut -d: -f2)" == "up" ]] && break
    sleep 1
  done
  # 重启哨兵清掉纪元与冷却
  for p in "${SPORTS[@]}"; do systemctl restart redis-lab-sentinel@$p; done
  sleep 6
  do_status
  return $ok
}

case "${1:-status}" in
  up)             do_up ;;
  down)           do_down ;;
  status)         do_status ;;
  promote-test)   promote_test ;;
  failover-test)  failover_test ;;
  # 二分定位：逐组把现役集群的配置项加回来，看哪一组会让故障转移失效
  up-rename)         STATE=rename;   do_up ;;
  up-ren-config)     STATE=ren-config;   do_up ;;
  up-ren-shutdown)   STATE=ren-shutdown; do_up ;;
  up-ren-flush)      STATE=ren-flush;    do_up ;;
  up-fixed)          STATE=ren-noconfig; do_up ;;
  up-announce)       STATE=announce; do_up ;;
  up-full)           STATE=full;     do_up ;;
  add-configs)       STATE=full;     do_up ;;
  *) echo "用法: $0 {up|down|status|promote-test|failover-test|up-rename|up-announce|up-full}"; exit 2 ;;
esac
