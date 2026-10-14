#!/bin/bash
##############################################################################
# llmall Redis HA 安装脚本 —— 在 VM（192.168.189.128）内以 root 执行
#
#   1 主（现役 6379，不改动）+ 1 从（6380）+ 3 哨兵（26379/26380/26381）
#
# 用法：
#   bash install-redis-ha.sh                 # 用默认值安装
#   bash install-redis-ha.sh --vm-ip 192.168.189.128 --password 123456
#   bash install-redis-ha.sh --uninstall     # 卸载（从库+哨兵，主库不动）
#   bash install-redis-ha.sh --status        # 只看状态
#   bash install-redis-ha.sh --repair        # 修复哨兵 monitor 行漂移（故障转移后必备）
#
# 【幂等性设计（很重要）】
#   哨兵在运行中会**自己重写配置文件**（写入 discovered replicas、current-epoch 等集群状态）。
#   因此对已存在的哨兵配置，本脚本绝不覆盖 —— 否则等于把集群状态抹掉，
#   三个哨兵会重新开始互相发现，期间无法达成多数派。
#   从库配置是无状态的，可以安全重生成。
##############################################################################
set -euo pipefail

VM_IP="192.168.189.128"
REDIS_PW="123456"
MASTER_PORT=6379
REPLICA_PORT=6380
SENTINEL_PORTS=(26379 26380 26381)
MASTER_NAME="llmall-master"

REPLICA_CONF="/etc/redis/redis-replica.conf"
SENTINEL_DIR="/etc/redis"
REPLICA_DATA="/var/lib/redis-replica"
SENTINEL_DATA="/var/lib/redis-sentinel"
LOG_DIR="/var/log/redis"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

ACTION="install"
DEBUG=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --vm-ip)     VM_IP="$2"; shift 2 ;;
    --password)  REDIS_PW="$2"; shift 2 ;;
    --uninstall) ACTION="uninstall"; shift ;;
    --status)    ACTION="status"; shift ;;
    --repair)    ACTION="repair"; shift ;;
    # 通过 vmrun 这类不回传 stdout 的通道调用时，用 --debug 把每一步落盘到
    # /tmp/dsh-ha-install.log，再用 copyFileFromGuestToHost 取回（否则失败原因不可见）。
    --debug)     DEBUG="1"; shift ;;
    *) echo "未知参数: $1"; exit 2 ;;
  esac
done

if [[ -n "$DEBUG" ]]; then
  exec > >(tee -a /tmp/dsh-ha-install.log) 2>&1
  set -x
fi

log()  { echo -e "\n\033[1;36m==> $*\033[0m"; }
ok()   { echo -e "    \033[1;32m[OK]\033[0m $*"; }
warn() { echo -e "    \033[1;33m[WARN]\033[0m $*"; }
die()  { echo -e "    \033[1;31m[FAIL]\033[0m $*"; exit 1; }

redis_cli() { redis-cli -h 127.0.0.1 -p "$1" -a "$REDIS_PW" --no-auth-warning "${@:2}"; }
sentinel_cli() { redis-cli -h 127.0.0.1 -p "$1" "${@:2}"; }

require_root() { [[ $EUID -eq 0 ]] || die "请用 root 执行"; }

# ---------------------------------------------------------------------------
status() {
  log "主库 6379"
  redis_cli $MASTER_PORT INFO replication 2>/dev/null | grep -E '^(role|connected_slaves|slave[0-9])' || warn "主库不可达"
  log "从库 ${REPLICA_PORT}"
  if ss -lnt 2>/dev/null | grep -q ":${REPLICA_PORT} "; then
    redis_cli $REPLICA_PORT INFO replication 2>/dev/null | grep -E '^(role|master_host|master_port|master_link_status|master_last_io_seconds_ago|slave_read_only)' || true
  else
    warn "从库未监听"
  fi
  for p in "${SENTINEL_PORTS[@]}"; do
    log "哨兵 $p"
    if ss -lnt 2>/dev/null | grep -q ":$p "; then
      echo "    报告主库: $(sentinel_cli "$p" SENTINEL get-master-addr-by-name "$MASTER_NAME" 2>/dev/null | paste -sd: - || true)"
      # SENTINEL replicas 的输出是扁平的 key:value 序列（ip: / port: 各自一行，成对出现），
      # 所以统计从库数量要数 "ip" 这个键出现的次数，而不是数以 ip 开头的行。
      echo "    已知从库: $(sentinel_cli "$p" SENTINEL replicas "$MASTER_NAME" 2>/dev/null | grep -c '^ip$' || true) 个"
      echo "    已下线主库: $(sentinel_cli "$p" SENTINEL masters "$MASTER_NAME" 2>/dev/null | grep -E '^(flags|num-other-sentinels)' | paste -sd' ' - || true)"
    else
      warn "哨兵 $p 未监听"
    fi
  done
  log "systemd 单元"
  systemctl is-active redis-replica 2>/dev/null || true
  for p in "${SENTINEL_PORTS[@]}"; do systemctl is-active "redis-sentinel@$p" 2>/dev/null || true; done
}

# ---------------------------------------------------------------------------
# 修复哨兵配置漂移。
#
# 【为什么需要这个动作】哨兵在**故障转移成功后会把自己的 monitor 行改写成新主库的地址**，
# 并把 config-epoch/current-epoch 递增。所以只要发生过一次切换，配置文件就"记住"了新主库：
#   sentinel monitor llmall-master 192.168.189.128 6380 1
# 此时如果你把拓扑改回 6379 主 / 6380 从（本项目 6379 是长期主库），
# 哨兵仍会去监控 6380 —— 而 6380 已经是 slave，于是三个哨兵全部报
# `flags=s_down,o_down,master`，其中一个卡在 `failover_in_progress`，怎么等都不收敛。
# 实测踩过这个坑，所以做成显式的修复入口，而不是让人手工 sed。
# ---------------------------------------------------------------------------
repair_sentinel() {
  require_root
  log "修复哨兵配置漂移（把 monitor 行改回 ${VM_IP}:${MASTER_PORT}，清零纪元，清掉已知从库）"
  for p in "${SENTINEL_PORTS[@]}"; do systemctl stop "redis-sentinel@$p" 2>/dev/null || true; done
  sleep 1
  for p in "${SENTINEL_PORTS[@]}"; do
    local f="${SENTINEL_DIR}/sentinel-${p}.conf"
    [[ -f "$f" ]] || { warn "$f 不存在，跳过（可先执行安装）"; continue; }
    local before
    before="$(grep -E '^sentinel monitor' "$f" | awk '{print $4":"$5}')"
    cp -f "$f" "${f}.bak.$(date +%s)"
    sed -i -e "s/^sentinel monitor ${MASTER_NAME} .*/sentinel monitor ${MASTER_NAME} ${VM_IP} ${MASTER_PORT} 1/" \
           -e "s/^sentinel config-epoch ${MASTER_NAME} .*/sentinel config-epoch ${MASTER_NAME} 0/" \
           -e "s/^sentinel leader-epoch ${MASTER_NAME} .*/sentinel leader-epoch ${MASTER_NAME} 0/" \
           -e 's/^sentinel current-epoch .*/sentinel current-epoch 0/' \
           -e "/^sentinel known-replica ${MASTER_NAME} /d" "$f"
    ok "sentinel-${p}: ${before} -> $(grep -E '^sentinel monitor' "$f" | awk '{print $4":"$5}')"
  done
  for p in "${SENTINEL_PORTS[@]}"; do systemctl start "redis-sentinel@$p"; done
  sleep 8
  local got=""
  for _ in $(seq 1 20); do
    got="$(sentinel_cli "${SENTINEL_PORTS[0]}" SENTINEL get-master-addr-by-name "$MASTER_NAME" 2>/dev/null | paste -sd: - || true)"
    [[ "$got" == "${VM_IP}:${MASTER_PORT}" ]] && break
    sleep 1
  done
  [[ "$got" == "${VM_IP}:${MASTER_PORT}" ]] \
    && ok "哨兵口径已回到 ${VM_IP}:${MASTER_PORT}" \
    || die "修复后哨兵仍认为主库是 ${got}，请检查 ${SENTINEL_DIR}/sentinel-*.conf 与日志"
  warn "提醒：修复只改哨兵认知，不改拓扑。若 6380 当前仍是 master，请先按 RUNBOOK 第 4 节把拓扑恢复到 6379 主。"
}

# ---------------------------------------------------------------------------
uninstall() {
  require_root
  log "停止并移除从库与哨兵（主库 6379 不动）"
  systemctl stop redis-replica 2>/dev/null || true
  for p in "${SENTINEL_PORTS[@]}"; do systemctl stop "redis-sentinel@$p" 2>/dev/null || true; done
  systemctl disable redis-replica 2>/dev/null || true
  for p in "${SENTINEL_PORTS[@]}"; do systemctl disable "redis-sentinel@$p" 2>/dev/null || true; done
  rm -f /etc/systemd/system/redis-replica.service /etc/systemd/system/redis-sentinel@.service
  systemctl daemon-reload
  rm -f "$REPLICA_CONF"
  for p in "${SENTINEL_PORTS[@]}"; do rm -f "${SENTINEL_DIR}/sentinel-${p}.conf"; done
  ok "已卸载。数据目录 ${REPLICA_DATA} / ${SENTINEL_DATA} 保留，需要彻底清理请手动 rm -rf"
  warn "如果哨兵曾经触发过故障转移（从库被提升为主库），请先确认 6379 上的实例是你想要的主库再继续使用"
}

# ---------------------------------------------------------------------------
do_install() {
  require_root

  log "0. 前置检查"
  command -v redis-server >/dev/null || die "未找到 redis-server"
  command -v redis-cli    >/dev/null || die "未找到 redis-cli"
  id redis >/dev/null 2>&1     || die "未找到 redis 用户"
  ok "redis-server $(redis-server --version | awk '{print $3}')"

  # 主库必须可达且确实是 master
  local role
  role="$(redis_cli $MASTER_PORT INFO replication 2>/dev/null | grep '^role:' | tr -d '\r' | cut -d: -f2 || true)"
  [[ "$role" == "master" ]] || die "主库 6379 角色是 '$role'，不是 master —— 请先确认环境（也许之前发生过故障转移）"
  ok "主库 6379 在线，角色 master"

  # 端口占用检查。
  # 【为什么要区分"自己的实例"和"别人的进程"】本脚本要支持**续装/重装**：
  # 上一次跑到一半失败（本次实测就发生过），从库可能已经起来并占着 6380。
  # 这时直接 die 会逼人先手工 systemctl stop，属于脚本自身不够健壮。
  # 判据用 systemd 单元归属：端口被占且对应单元 active → 是我们自己的，跳过即可；
  # 被别的进程占 → 才是真冲突，必须报错（否则会起不来，还得回来查）。
  port_owned_by_us() {
    local svc="$1"
    systemctl is-active --quiet "$svc" 2>/dev/null
  }
  for p in "${REPLICA_PORT}" "${SENTINEL_PORTS[@]}"; do
    if ss -lnt 2>/dev/null | grep -q ":$p "; then
      if [[ "$p" == "$REPLICA_PORT" ]] && port_owned_by_us redis-replica; then
        warn "端口 ${p} 已被本方案的从库（redis-replica）占用 —— 视为续装，将重启该实例"
        REPLICA_ALREADY_RUNNING=1
      elif port_owned_by_us "redis-sentinel@$p"; then
        warn "端口 ${p} 已被本方案的哨兵（redis-sentinel@${p}）占用 —— 视为续装，将重启该实例"
      else
        die "端口 $p 被其它进程占用，请先处理（ss -lntp | grep :$p）"
      fi
    fi
  done
  if [[ "${REPLICA_ALREADY_RUNNING:-0}" == "1" ]]; then
    ok "端口检查：从库续装，哨兵端口已就绪"
  else
    ok "端口 ${REPLICA_PORT} / ${SENTINEL_PORTS[*]} 均空闲"
  fi

  # 磁盘：从库要存一份数据 + RDB，巡检发现根分区经常只剩 3G 上下
  local avail_mb
  avail_mb="$(df -Pm / | awk 'NR==2 {print $4}')"
  if (( avail_mb < 512 )); then
    die "根分区可用空间仅 ${avail_mb}MB，不足以安全地新增一个 Redis 实例"
  elif (( avail_mb < 1536 )); then
    warn "根分区可用空间仅 ${avail_mb}MB（建议 ≥1536MB）—— 从库已按保守策略配置持久化，但仍请留意"
  else
    ok "根分区可用 ${avail_mb}MB"
  fi

  log "1. 创建目录"
  mkdir -p "$REPLICA_DATA" "$SENTINEL_DATA" "$LOG_DIR"
  chown -R redis:redis "$REPLICA_DATA" "$SENTINEL_DATA" "$LOG_DIR"
  chmod 750 "$REPLICA_DATA" "$SENTINEL_DATA"
  ok "$REPLICA_DATA / $SENTINEL_DATA / $LOG_DIR"

  log "2. 生成从库配置（无状态，可安全重生成）"
  [[ -f "$SCRIPT_DIR/redis-replica.conf.template" ]] || die "缺少 $SCRIPT_DIR/redis-replica.conf.template"
  sed -e "s/__VM_IP__/${VM_IP}/g" \
      -e "s/^masterauth .*/masterauth ${REDIS_PW}/" \
      -e "s/^requirepass .*/requirepass ${REDIS_PW}/" \
      "$SCRIPT_DIR/redis-replica.conf.template" > "$REPLICA_CONF"
  chown redis:redis "$REPLICA_CONF"
  chmod 640 "$REPLICA_CONF"
  # 语法预检：这里**不**调用 `redis-server --check-config`。
  # 实测（Redis 7.0.15 / MSYS2 构建）它不接受这种用法：
  #   `redis-server <conf> --check-config` → 把 check-config 当成配置指令，报 Bad directive；
  #   `redis-server --check-config <conf>` → 同样报 Bad directive。
  # 与其和一个不确定的 CLI 参数较劲，不如用**行为**校验：下面第 5 步会真正启动实例，
  # 配置有错就起不来（失败原因会进 journalctl -u redis-replica，比预检输出更好定位）。
  # 这里只做一条廉价的价值检查：确认模板里的占位符已被全部替换（漏替换会导致启动失败且难查）。
  if grep -q '__VM_IP__' "$REPLICA_CONF"; then
    die "配置里仍有未替换的 __VM_IP__ 占位符：$REPLICA_CONF"
  fi
  # 自检：绝不接受 rename-command CONFIG —— 它会让哨兵自动故障转移静默失效（详见模板注释）
  if grep -qE '^[[:space:]]*rename-command[[:space:]]+CONFIG' "$REPLICA_CONF"; then
    die "配置里含有 'rename-command CONFIG'，它会让哨兵故障转移永远失败（已用最小复现逐项验证）。
     请删除该行；安全加固请依赖 requirepass + 内网隔离。"
  fi
  ok "$REPLICA_CONF（replica-announce-ip=${VM_IP}，占位符已替换，未禁用 CONFIG）"

  log "3. 生成哨兵配置（已存在则**绝不覆盖**，详见脚本头部说明）"
  [[ -f "$SCRIPT_DIR/sentinel.conf.template" ]] || die "缺少 $SCRIPT_DIR/sentinel.conf.template"
  local sentinel_created=0 sentinel_kept=0
  for p in "${SENTINEL_PORTS[@]}"; do
    local f="${SENTINEL_DIR}/sentinel-${p}.conf"
    if [[ -f "$f" ]]; then
      # 已经存在：保留（哨兵在其中维护了集群状态）
      sed -i "s/^sentinel auth-pass ${MASTER_NAME} .*/sentinel auth-pass ${MASTER_NAME} ${REDIS_PW}/" "$f" 2>/dev/null || true
      sentinel_kept=$((sentinel_kept+1))
      continue
    fi
    sed -e "s/^port .*/port ${p}/" \
        -e "s#^pidfile .*#pidfile /run/redis/sentinel-${p}.pid#" \
        -e "s#^logfile .*#logfile ${LOG_DIR}/sentinel-${p}.log#" \
        -e "s/__VM_IP__/${VM_IP}/g" \
        -e "s/^sentinel auth-pass ${MASTER_NAME} .*/sentinel auth-pass ${MASTER_NAME} ${REDIS_PW}/" \
        "$SCRIPT_DIR/sentinel.conf.template" > "$f"
    chown redis:redis "$f"
    chmod 640 "$f"
    sentinel_created=$((sentinel_created+1))
  done
  ok "新建 ${sentinel_created} 份，保留已有 ${sentinel_kept} 份"

  log "4. 安装 systemd 单元"
  # 从库
  cat > /etc/systemd/system/redis-replica.service <<EOF
[Unit]
Description=Redis replica for llmall (port ${REPLICA_PORT})
After=network.target

[Service]
Type=notify
User=redis
Group=redis
# 时区：Redis 没有 timezone 配置指令，日志时间戳的时区只能靠进程 TZ（本项目统一东八区）
Environment="TZ=Asia/Shanghai"
ExecStart=/usr/bin/redis-server ${REPLICA_CONF}
ExecStop=/usr/bin/redis-cli -p ${REPLICA_PORT} -a ${REDIS_PW} --no-auth-warning SHUTDOWN_9f3a1c
Restart=always
RestartSec=3
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
EOF

  # 哨兵（模板单元，用 %i 传端口）
  cat > /etc/systemd/system/redis-sentinel@.service <<'EOF'
[Unit]
Description=Redis Sentinel on port %i for llmall
After=network.target

[Service]
Type=notify
User=redis
Group=redis
Environment="TZ=Asia/Shanghai"
# 【为什么不用 redis-sentinel】Ubuntu 24.04 的 redis-server 包**不提供** redis-sentinel 可执行文件
# （实测 dpkg -L redis-server 只有 redis-server/redis-cli/redis-check-*/redis-benchmark，
#  而 /usr/bin/redis-server 本身还是个指向 redis-check-rdb 的软链）。
# 用 redis-sentinel 启动会得到 systemd 的 status=203/EXEC（"No such file or directory"），
# 而且失败原因只在 journalctl 里，很容易被误判成配置错误。
# 正确做法是让同一个二进制进入哨兵模式：redis-server <conf> --sentinel。
ExecStart=/usr/bin/redis-server /etc/redis/sentinel-%i.conf --sentinel
Restart=always
RestartSec=3
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
EOF

  systemctl daemon-reload
  systemctl enable redis-replica >/dev/null 2>&1
  for p in "${SENTINEL_PORTS[@]}"; do systemctl enable "redis-sentinel@$p" >/dev/null 2>&1; done
  ok "redis-replica.service + redis-sentinel@.service"

  log "5. 启动从库"
  systemctl restart redis-replica
  sleep 2
  local link
  for _ in $(seq 1 15); do
    link="$(redis_cli $REPLICA_PORT INFO replication 2>/dev/null | grep '^master_link_status:' | tr -d '\r' | cut -d: -f2 || true)"
    [[ "$link" == "up" ]] && break
    sleep 1
  done
  [[ "$link" == "up" ]] || die "从库与主库的复制链路未建立（master_link_status=$link），请查看 ${LOG_DIR}/redis-replica.log"
  ok "复制链路 up：$(redis_cli $REPLICA_PORT INFO replication | grep -E '^(role|master_host|master_port)' | paste -sd' ' -)"

  log "6. 启动 3 个哨兵"
  for p in "${SENTINEL_PORTS[@]}"; do systemctl restart "redis-sentinel@$p"; done
  sleep 3

  # 等哨兵互相发现并确认主库
  local discovered=""
  for _ in $(seq 1 20); do
    discovered="$(sentinel_cli ${SENTINEL_PORTS[0]} SENTINEL get-master-addr-by-name "$MASTER_NAME" 2>/dev/null | paste -sd: - || true)"
    [[ -n "$discovered" ]] && break
    sleep 1
  done
  [[ -n "$discovered" ]] || die "哨兵未能识别主库 ${MASTER_NAME}，请查看 ${LOG_DIR}/sentinel-*.log"
  ok "哨兵识别到主库：$discovered"

  log "7. 汇总校验"
  local sentinel_count=0
  for p in "${SENTINEL_PORTS[@]}"; do
    ss -lnt 2>/dev/null | grep -q ":$p " && sentinel_count=$((sentinel_count+1))
  done
  ok "哨兵在线 ${sentinel_count}/3（需要 ≥2 才能达成多数派）"

  local known_replicas
  known_replicas="$(sentinel_cli ${SENTINEL_PORTS[0]} SENTINEL replicas "$MASTER_NAME" 2>/dev/null | grep -c '^ip$' || true)"
  ok "哨兵已知从库数量：${known_replicas}"

  # 【最重要的验收点】确认哨兵下发给客户端的地址是"应用能连到的地址"。
  # 如果这里是 127.0.0.1，Windows 上的服务会被引导去连自己 —— 必须立刻拦住。
  if [[ "$discovered" == "127.0.0.1:"* ]]; then
    die "哨兵报出的主库地址是 ${discovered}，应用在 Windows 宿主上会被引导去连自己的 Redis！
     处置：确认 sentinel-*.conf 里的 monitor 地址用的是 VM 真实 IP（当前 --vm-ip=${VM_IP}），
     然后执行 --uninstall 后重新安装（哨兵会自己重写配置，不能只 sed 一行）。"
  fi
  ok "哨兵下发的地址可被应用使用：${discovered}"

  log "完成"
  echo "    应用侧连接串改用哨兵："
  echo "      spring.redis.sentinel.master=${MASTER_NAME}"
  echo "      spring.redis.sentinel.nodes=${VM_IP}:26379,${VM_IP}:26380,${VM_IP}:26381"
  echo "      spring.redis.password=${REDIS_PW}"
  echo "    查看状态：bash $0 --status"
  echo "    演练切换：bash $0 --status 之后根据运行手册 failover-drill 一节操作"
}

case "$ACTION" in
  install)   do_install ;;
  uninstall) uninstall ;;
  status)    status ;;
  repair)    repair_sentinel ;;
esac
