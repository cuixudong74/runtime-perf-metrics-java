# Runtime Performance Metrics

运行期性能指标采集组件：在请求处理过程中以非阻塞方式记录耗时与结果状态，
按滑动时间窗口聚合，并对外提供某一时刻自洽的不可变快照。

## 快速开始

```java
MetricsCollector collector = new MetricsCollector(MetricsConfig.defaults());

long start = collector.startNanos();
try {
    // ... 业务处理 ...
    collector.record("order.create", System.nanoTime() - start, RequestResult.SUCCESS);
} catch (RuntimeException e) {
    collector.record("order.create", System.nanoTime() - start, RequestResult.FAILURE);
    throw e;
}

WindowSnapshot snapshot = collector.snapshot();   // 某一时刻的自洽快照
```

Spring Boot 环境下 `MetricsCollector` 自动注册为 Bean，并暴露查询端点：

```
GET /metrics/snapshot
```

## 指标语义

每个维度（dimension）在窗口内聚合出以下统计量（`WindowSnapshot.DimensionStats`）：

| 字段 | 含义 |
|---|---|
| `totalCount` / `successCount` / `failureCount` | 窗口内请求总数 / 成功数 / 失败数，恒有 `total = success + failure` |
| `totalLatencyNanos` | 窗口内耗时总和（纳秒），配合总数可得均值与吞吐 |
| `minLatencyNanos` / `maxLatencyNanos` | 窗口内最小 / 最大耗时 |
| `p50Nanos` / `p90Nanos` / `p99Nanos` | 基于桶内有界蓄水池样本的最近秩分位数；样本数未超上限时为精确值 |

快照级字段：`capturedAtMillis`（快照时刻）、`windowStartMillis` / `windowEndMillis`
（快照对应的窗口区间）、`dimensions`（维度名 → 统计量，不可变 Map）。

`record(...)` 返回 `RecordOutcome`，用于区分采集结果：

- `RECORDED`：正常记录；
- `SAMPLED_OUT`：被采样率丢弃；
- `CARDINALITY_DEGRADED`：维度基数超限，已归并到保留维度 `__other__`；
- `CARDINALITY_REJECTED`：维度基数超限且策略为拒绝，本次记录被丢弃；
- `FAILED`：非法参数或内部异常（已隔离，不影响业务）。

## 一致性边界

- **写入**：单次 `record` 只触碰当前时间桶；桶内一组计数器的更新在桶级监视器下
  原子完成（临界区仅含若干次内存加法），高并发下不丢失、不重复计数
  （有并发测试验证精确总数）。
- **读取**：快照逐桶拷贝自洽副本（每桶在自己的监视器下原子拷贝），
  得到的是对应时刻的不可变结果；跨窗口轮转边界读取时不会看到半个窗口或撕裂的统计值。
  一致性边界：单桶内严格原子，跨桶为逐桶依次拷贝，正在进行的写入可能只反映在部分桶中，
  但任何单桶内部不会出现 `total != success + failure` 之类的撕裂。
- **轮转**：周期检查、过期桶重置与写入在同一桶级临界区内原子完成，
  轮转边界上的写入要么落入旧周期末尾、要么落入新周期开头。
- **窗口**：窗口为定长环形桶数组（窗口长度 ÷ 桶数 = 桶长），轮转时仅重置过期桶，
  内存占用有界，长时间运行不会无界增长。
- **分位数**：每桶保留至多 `maxSamplesPerBucket` 个蓄水池样本，超出后等概率替换，
  保证内存有界且统计无偏。
- **配置变更**：`reconfigure` 以 volatile 语义整体替换配置，只影响之后的采集与聚合；
  实时窗口按新参数重建，已生成的快照为不可变副本，绝不被追溯改写。
- **异常隔离**：采集与聚合路径上的任何异常都被捕获并转化为 `FAILED` 返回，
  单条记录失败不影响其他记录，异常不会逃逸到业务请求链路。

## 配置项

`application.properties`（前缀 `metrics.`）或编程式 `MetricsConfig`：

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `metrics.windowLengthMillis` | `60000` | 滑动窗口长度（毫秒） |
| `metrics.bucketCount` | `60` | 窗口内桶数量，桶长 = 窗口长度 / 桶数 |
| `metrics.sampleRate` | `1.0` | 采样率 (0,1]，0 表示全部丢弃 |
| `metrics.maxDimensions` | `1024` | 维度基数上限 |
| `metrics.overflowPolicy` | `DEGRADE_TO_OTHER` | 超限策略：`DEGRADE_TO_OTHER` 或 `REJECT` |
| `metrics.maxSamplesPerBucket` | `4096` | 每桶分位数样本上限（蓄水池） |

## 验证方法

```bash
mvn test
```

自动化测试覆盖：

- `SlidingWindowTest`：窗口聚合、轮转淘汰过期数据、分位数计算、空窗口；
- `MetricsCollectorConcurrencyTest`：16 线程并发写入总数精确（不丢失不重复）、
  写入期间跨轮转边界连续读取快照的内部自洽性；
- `MetricsCollectorConfigTest`：配置变更只影响之后采集且旧快照不被改写、
  采样率生效、基数超限降级/拒绝及可区分原因、非法输入异常隔离；
- `MetricsCollectorOverheadTest`：8 线程高并发下单次采集平均开销可度量且有界
  （输出 ns/op，断言平均值 < 10µs）；
- `MetricsEndpointTest`：HTTP 端点暴露已记录指标。
