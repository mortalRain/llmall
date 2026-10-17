#!/bin/sh
# redis 容器的启动脚本：一个容器里同时跑 Redis 主节点与 3 个哨兵。
#
# 为什么把哨兵塞进 redis 容器，而不是各起一个容器：
#   nacos-config/shared-redis.yaml 把哨兵地址写成 ${llmall.redis.host}:26379/26380/26381，
#   也就是"哨兵与 redis 同机、同主机名"。VM / 生产上本来就是这么部署的（原生安装，同一台机器）。
#   若把哨兵拆成 sentinel1/2/3 三个容器，应用按 redis:26379 连的就是 redis 容器本身的 26379 端口
#   （没人监听）→ 连接被拒，所有服务启动即刷 "Cannot connect Redis Sentinel"。
#   放进同一个容器后，应用连 redis:26379 才真的是连到哨兵，仓库里的配置一个字都不用改。
#
# 哨兵要点：
#   resolve-hostnames / announce-hostnames 必须打开。哨兵默认只接受 IP 形式的目标，
#   配置里写主机名 redis 会直接以 "Can't resolve instance hostname" 退出；打开后哨兵也以主机名
#   把主库地址回报给客户端，客户端在容器网络里按名字连，不依赖易变的容器 IP。
#   auth-pass 是哨兵连*数据节点*用的口令（数据节点有 requirepass；哨兵自身不设口令）。

set -e

PASSWORD="${REDIS_PASSWORD:-123456}"

echo "[redis-entrypoint] 启动 redis-server（口令 ${#PASSWORD} 位，关闭 RDB/AOF 持久化）"
redis-server \
  --requirepass "$PASSWORD" \
  --maxmemory 96mb \
  --maxmemory-policy allkeys-lru \
  --save "" \
  --appendonly no &
REDIS_PID=$!

i=0
while [ "$i" -lt 30 ]; do
  if redis-cli -a "$PASSWORD" --no-auth-warning ping 2>/dev/null | grep -q PONG; then
    echo "[redis-entrypoint] redis-server 就绪"
    break
  fi
  i=$((i + 1))
  sleep 1
done

for port in 26379 26380 26381; do
  conf="/tmp/sentinel-${port}.conf"
  cat > "$conf" <<EOF
port ${port}
dir /tmp
sentinel resolve-hostnames yes
sentinel announce-hostnames yes
sentinel monitor llmall-master redis 6379 1
sentinel auth-pass llmall-master ${PASSWORD}
sentinel down-after-milliseconds llmall-master 5000
sentinel failover-timeout llmall-master 60000
EOF
  echo "[redis-entrypoint] 启动哨兵 :${port}（监视 llmall-master = redis:6379）"
  redis-sentinel "$conf" &
done

# 任一进程退出即整体退出：让编排层看到容器异常，而不是"主进程还活着但哨兵全死了"
wait -n 2>/dev/null || wait
