# Runtime Performance Metrics for Java

一套面向 Java/Spring Boot 服务的运行期请求性能指标采集组件。它在请求完成后记录耗时与结果状态，通过有界异步队列交给单线程聚合器，按固定桶滑动窗口输出不可变快照。

## 能力概览

- **异步非阻塞采集**：业务线程只做参数校验、采样、有界数量的维度复制和 `Queue#offer`，不等待窗口聚合。
- **固定桶滑动窗口**：窗口由固定数量的时间桶组成；轮转时复用桶对象并清空旧聚合，长时间运行不会保留无限样本。
- **总量与分位数**：输出采样数量、按采样率折算后的估计数量、总耗时、最小/最大耗时、状态计数以及 P50/P90/P95/P99。
- **维度分组**：按不可变、排序后的维度 Map 分组；每个桶的维度基数有硬上限。
- **基数保护**：超出上限时可拒绝或合并到降级组，原因通过 `Admission` 与快照中的 `dropped` 区分。
- **配置屏障**：配置对象不可变；配置更新作为队列事件在聚合线程串行生效，只影响后续采集和聚合。
- **异常隔离**：单条记录失败只返回拒绝原因或增加丢弃计数，不向业务调用抛出指标异常。
- **只读 HTTP 快照**：自动暴露 `GET /actuator/metrics/request-runtime`。

## 快速使用

### 编程方式

```java
MetricCollector collector = new SlidingWindowMetricCollector(
        MetricsConfig.defaults(),
        Clock.systemUTC()
);

Admission admission = collector.recordLatency(
        durationNanos,
        RequestStatus.SUCCESS,
        Map.of("route", "/orders", "statusClass", "2xx")
);

MetricSnapshot snapshot = collector.snapshot();
```

无维度时可以使用便捷方法：

```java
collector.recordLatency(durationNanos, RequestStatus.ERROR);
```

Spring Boot 应用会通过 `MetricsAutoConfiguration` 自动注册 `MetricCollector` 和只读控制器。

### 查询快照

```bash
curl -s http://localhost:8080/actuator/metrics/request-runtime
```

返回字段示例：

```json
{
  "windowStart": "2026-01-01T00:00:00Z",
  "windowEnd": "2026-01-01T00:01:00Z",
  "capturedAt": "2026-01-01T00:00:30.123Z",
  "sampleCount": 1200,
  "estimatedCount": 1200.0,
  "totalDurationNanos": 84000000.0,
  "minDurationNanos": 1000,
  "maxDurationNanos": 82000000,
  "sampleStatusCounts": { "SUCCESS": 1180, "ERROR": 20 },
  "estimatedStatusCounts": { "SUCCESS": 1180.0, "ERROR": 20.0 },
  "quantilesNanos": { "0.5": 12000.0, "0.9": 45000.0, "0.95": 90000.0, "0.99": 350000.0 },
  "groups": {
    "{route=/orders}": {
      "sampleCount": 1200,
      "estimatedCount": 1200.0,
      "totalDurationNanos": 84000000.0,
      "minDurationNanos": 1000,
      "maxDurationNanos": 82000000,
      "sampleStatusCounts": { "SUCCESS": 1180, "ERROR": 20 },
      "estimatedStatusCounts": { "SUCCESS": 1180.0, "ERROR": 20.0 },
      "quantilesNanos": { "0.5": 12000.0, "0.9": 45000.0, "0.95": 90000.0, "0.99": 350000.0 }
    }
  },
  "dropped": {
    "QUEUE_FULL": 3,
    "DIMENSION_CARDINALITY_EXCEEDED": 17
  }
}
```

> Java 记录序列化为 JSON 时，顶层 Map 键的实际文本由 Jackson 的 Map 键序列化规则决定；字段语义不变。

## 指标语义

| 字段 | 含义 |
| --- | --- |
| `sampleCount` | 实际进入窗口聚合并仍在窗口内的样本数，不包含被采样丢弃、队列拒绝或过期的记录。 |
| `estimatedCount` | 按采样率折算后的请求数。每个样本权重为 `1 / sampleRate`。 |
| `totalDurationNanos` | 按采样权重折算后的总耗时，单位纳秒。 |
| `minDurationNanos` / `maxDurationNanos` | 窗口内实际样本的最小/最大耗时，不做样本权重放大。 |
| `sampleStatusCounts` | 实际样本按 `RequestStatus` 的计数。 |
| `estimatedStatusCounts` | 按采样权重折算后的状态计数。 |
| `quantilesNanos` | P50、P90、P95、P99，单位纳秒。 |
| `groups` | 按维度分组的同类聚合。空维度不产生额外分组，只计入顶层总计。 |
| `dropped` | 进程内累计的非采样拒绝/失败计数。`SAMPLED_OUT` 属于正常采样，不计入丢弃。 |
| `windowStart` / `windowEnd` | 当前窗口的半开时间范围：`[windowStart, windowEnd)`。 |
| `capturedAt` | 快照捕获时使用的时钟时间。 |

吞吐率不是单独存储字段，可由快照一致计算：

```text
throughput = estimatedCount / (windowEnd - windowStart)
平均耗时   = totalDurationNanos / estimatedCount
```

### 分位数算法

为避免保留每个请求导致内存无界，分位数由有界稀疏对数直方图估算。每个二进制约有 32 个桶，桶内按对数位置插值。它的优点是：

- 内存上限只和可能非空的固定桶数量有关，不随请求数增长；
- 支持采样权重；
- 可以线性合并多个时间桶。

该分位数是**相对误差很小的估算值**，不对每个样本做精确排序。若业务需要绝对精确 P99，需要保留全量样本，不适合长时间运行的低开销在线采集。

## 一致性边界

### 写入语义

- 业务线程得到 `ACCEPTED` 表示事件已经进入有界队列，聚合将由后台线程异步完成。
- 如果聚合阶段发现维度超限或事件已过期，事件不会计入窗口，并通过 `dropped` 中对应原因暴露。
- 事件由单消费者处理，每条入队事件最多聚合一次，不会重复计数。
- `QUEUE_FULL`、`INVALID_ARGUMENT`、`SHUTDOWN` 等在业务线程即可判定的原因会立即返回。

### 快照语义

- 聚合、窗口轮转和快照合并都在同一个窗口监视器内执行。
- 快照在轮转完成后一次性合并当前所有桶，不会读到半个正在轮转的桶。
- 返回的 `MetricSnapshot` 及其内部 Map 均为不可变视图；之后窗口继续轮转或配置变更不会改写已返回对象。
- 快照中的窗口数据对应捕获时刻；`dropped` 是进程级累计诊断计数，可能包含当前窗口之外的历史拒绝。

### 配置变更语义

配置通过不可变 `MetricsConfig` 表示，并作为队列屏障由聚合线程应用：

- `enabled`、`sampleRate`、基数策略等不改变时间桶结构的配置生效后，只影响后续事件，已有快照不变。
- 窗口长度或桶长度变化会建立新的时间桶结构；已返回快照保持不变，新快照从新结构产生。为保证边界清晰，这类结构变更不尝试拆分旧桶。
- 采样率在业务线程读取。配置成功入队后，新请求可能立即使用新采样率，聚合线程应用屏障前后存在一个很短的异步过渡窗口；该过渡不影响旧快照。
- 队列容量在采集器构造时确定并绑定有界队列，运行期配置中的容量不会动态重建队列。

## 维度与基数策略

维度在业务线程被复制为按 key 排序的不可变 `TreeMap`，以保证不同调用方传入不同 Map 实现时分组稳定。维度数量受 `max-dimensions-per-record` 限制；单个桶内的分组数量受 `max-groups-per-bucket` 限制。

- `REJECT`：新维度组不进入聚合，丢弃原因是 `DIMENSION_CARDINALITY_EXCEEDED`。
- `DEGRADE`：达到上限后把一个既有正常组合并进降级组，后续超限样本继续进入该组。降级组默认为 `{ "__degraded": "true" }`。

降级策略保证一个桶内分组数严格不超过上限，避免高基数维度拖垮内存和快照合并。

## 异常隔离

`recordLatency` 捕获指标采集自身的运行时异常，不向业务链路抛出：

- 负耗时、空状态、空维度、空 key/value、维度数量超限：`INVALID_ARGUMENT`；
- 采集器关闭后写入：`SHUTDOWN`；
- 有界队列已满：`QUEUE_FULL`；
- 后台处理单条事件失败：`INTERNAL_ERROR`；
- 事件到达聚合器时已滑出窗口：`EXPIRED`。

后台工作线程为 daemon 线程。Spring Bean 销毁或调用 `close()` 时会停止接收新事件并中断/等待工作线程退出。

## 配置项

Spring Boot 属性如下：

| 属性 | 默认值 | 说明 |
| --- | --- | --- |
| `perf.metrics.enabled` | `true` | 是否接受新样本。关闭后返回 `DISABLED`。 |
| `perf.metrics.sample-rate` | `1.0` | 采样率，范围 `[0,1]`；`0` 表示全部采样丢弃。 |
| `perf.metrics.window-length` | `1m` | 滑动窗口长度。 |
| `perf.metrics.bucket-length` | `1s` | 单个时间桶长度，窗口长度必须是其整数倍。 |
| `perf.metrics.max-groups-per-bucket` | `1000` | 每桶最大维度组数。 |
| `perf.metrics.max-dimensions-per-record` | `16` | 单次记录允许的维度键数量上限。 |
| `perf.metrics.cardinality-policy` | `DEGRADE` | `DEGRADE` 或 `REJECT`。 |
| `perf.metrics.queue-capacity` | `131072` | 业务线程到聚合线程的有界队列容量。 |
| `perf.metrics.degraded-dimension-name` | `__degraded` | 降级组维度名。 |
| `perf.metrics.degraded-dimension-value` | `true` | 降级组维度值。 |

示例：

```properties
perf.metrics.enabled=true
perf.metrics.sample-rate=0.2
perf.metrics.window-length=2m
perf.metrics.bucket-length=5s
perf.metrics.max-groups-per-bucket=500
perf.metrics.max-dimensions-per-record=12
perf.metrics.cardinality-policy=REJECT
perf.metrics.queue-capacity=65536
```

## 性能与内存特征

业务线程的工作为：

1. 常数次参数检查和一次采样随机数；
2. 复制最多 `maxDimensionsPerRecord` 个维度；
3. 创建一个小事件对象；
4. 对 `LinkedBlockingQueue` 执行非阻塞 `offer`。

时间复杂度为 `O(D)`，其中 `D` 为受配置限制的维度数量；不会执行分位数计算、快照合并或窗口轮转。队列满时立即返回 `QUEUE_FULL`，不阻塞业务线程。

内存上界主要为：

```text
窗口桶数 × (每桶最大分组数 × 固定大小聚合对象 + 有界非空直方图桶)
+ queue-capacity × 排队事件大小
```

窗口桶和维度 Map 在轮转时释放旧聚合，不会随进程运行时间线性增长。

## 验证方法

运行全部测试：

```bash
mvn test
```

手动测量业务线程平均采集开销（普通测试默认不运行，避免不同 CI 机器上的脆弱性能断言）。推荐在已编译的 IDE 中直接运行 `MetricRecordOverheadHarness#main`；命令行可自行按本机依赖构造 test classpath：

```bash
mvn -q -DskipTests test-compile
java -cp "target/classes:target/test-classes:${LOCAL_TEST_CLASSPATH}" \
  com.github.cuixudong74.metrics.perf.MetricRecordOverheadHarness
```

该 harness 只测量 `recordLatency` 在业务线程中的入队开销，不等待后台聚合，因而对应“采集是否拖慢请求”的关键路径。正式基准测试建议在独立 JMH 模块中结合具体硬件、JDK、维度数量和队列竞争情况执行。

覆盖内容：

- `SlidingWindowMetricCollectorTest`：16 线程并发写入，验证总量精确无重复、无丢失，以及单条异常记录隔离；
- `SlidingWindowMetricCollectorWindowTest`：手动推进时钟，验证桶轮转、过期淘汰、窗口边界和旧快照不可变；
- `LogarithmicHistogramTest`：验证加权样本、零值、P50/P90/P95/P99 的估算边界；
- `ConfigurationChangeTest`：验证采样率变更只影响后续样本，无效配置不生效，旧快照不被追溯改写；
- `CardinalityTest`：验证降级和拒绝策略及可区分原因；
- `MetricsEndpointTest`：验证 Spring Boot 自动配置和 HTTP 快照端点；
- `MetricRecordOverheadHarness`：手动运行的业务线程开销测量入口，不作为默认断言测试。

## 包结构

```text
com.github.cuixudong74.metrics
├── MetricCollector                     # 公共采集接口
├── SlidingWindowMetricCollector        # 异步有界采集实现
├── MetricsConfig / MetricsProperties   # 不可变配置与 Spring 属性绑定
├── MetricSnapshot / GroupSnapshot      # 不可变快照
├── RequestStatus / Admission           # 状态与准入/拒绝原因
├── SlidingWindow / WindowBucket        # 固定桶滑动窗口
├── Aggregate                           # 单组总量、状态、极值聚合
├── LogarithmicHistogram                # 有界分位数估算
└── MetricsController                   # 只读 HTTP 端点
```
