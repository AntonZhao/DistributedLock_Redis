## 1. 为何需要分布式锁

在分布式环境中，需要一种`跨JVM的互斥机制`来控制共享资源的访问。

- 例如，**为避免用户操作重复导致交易执行多次**，使用分布式锁可以将第一次以外的请求在所有服务节点上立马取消掉。如果使用事务在数据库层面进行限制也能实现的但会增大数据库的压力。
- 例如，在分布式任务系统中为避免统一任务重复执行，某个节点执行任务之后可以使用分布式锁避免其他节点在同一时刻得到任务

## 2. 如何实现

首先我们需要一个简单的答题套路：需求分析、系统设计、实现方式、缺点不足

### 2.1 需求分析

1. 能够在高并发的分布式的系统中应用
2. 需要实现锁的基本特性：一旦某个锁被分配出去，那么其他的节点无法再进入这个锁所管辖范围内的资源；失效机制避免无限时长的锁与死锁
3. 进一步实现锁的高级特性和JUC并发工具类似功能更好：可重入、阻塞与非阻塞、公平与非公平、JUC的并发工具（Semaphore, CountDownLatch, CyclicBarrier）

### 2.2 系统设计

转换成设计是如下几个要求：

1. 对加锁、解锁的过程需要是高性能、原子性的
2. 需要在某个分布式节点都能访问到的公共平台上进行锁状态的操作

所以，我们分析出系统的构成应该要有**锁状态存储模块**、**连接存储模块的连接池模块**、**锁内部逻辑模块**

#### 锁状态存储模块

分布式锁的存储有三种常见实现，因为能满足实现锁的这些条件：高性能加锁解锁、操作的原子性、是分布式系统中不同节点都可以访问的公共平台：

1. 数据库（利用主键唯一规则、MySQL行锁）

   `由于锁常常是在高并发的情况下才会使用到的分布式控制工具，所以使用数据库实现会对数据库造成一定的压力，连接池爆满问题，所以不推荐数据库实现；`

2. Zookeeper临时有序节点

   `还需要维护Zookeeper集群，实现起来还是比较复杂的。如果不是原有系统就依赖Zookeeper，同时压力不大的情况下。一般不使用Zookeeper实现分布式锁。`

3. 基于Redis的NX、EX参数

   `所以缓存实现分布式锁还是比较常见的，因为**缓存比较轻量、缓存的响应快、吞吐高、还有自动失效的机制保证锁一定能释放。`

参考：[三种分布式锁实现方式的详细对比](https://github.com/AntonZhao/DistributedLock_Redis/blob/master/分布式锁的几种实现方式.md)

#### 连接池模块

- 可使用JedisPool实现，如果后期性能不佳，可考虑参照HikariCP自己实现

#### 锁内部逻辑模块

- 基本功能：加锁、解锁、超时释放
- 高级功能：可重入、阻塞与非阻塞、公平与非公平、JUC并发工具功能

### 2.3 实现方式

存储模块使用Redis，连接池模块暂时使用JedisPool，锁的内部逻辑将从基本功能开始，逐步实现高级功能，下面就是各种功能实现的具体思路与代码了。

#### 加锁、超时释放

`NX`是Redis提供的一个`原子操作`

- 如果指定key存在，那么NX失败，如
- 果不存在，会进行set操作并返回成功。

我们可以利用这个来实现一个分布式的锁，主要思路就是，set成功表示获取锁，set失败表示获取失败，失败后需要重试。再加上`EX参数`可以让该key在超时之后自动删除。

- `lock` -> 调用后一直阻塞到获得锁，可以加上过期时间

* `tryLock` -> 尝试是否能获得锁 如果不能获得立即返回
- `lockInterruptibly` -> 调用后一直阻塞到获得锁 但是接受中断信号  -> 这是JUC里面的，这里暂不实现

```java
// 尝试获取锁
public void lock(String key, String value) throws InterruptedException {
    Jedis jedis = jedisPool.getResource();
    while (true) {
        if (setLockToRedis(key, value, jedis)) return;
    }
}

// 在一定时间范围内去获取锁
public void lock(String key, String value, int timeout) throws InterruptedException {
    Jedis jedis = jedisPool.getResource();
    while (timeout >= 0) {
        if (setLockToRedis(key, value, jedis)) return;
        timeout -= DEFAULT_SLEEP_TIME;
    }
}

public boolean tryLock(String key, String value) throws InterruptedException {
    Jedis jedis = jedisPool.getResource();
    return setLockToRedis(key, value, jedis);
}

private boolean setLockToRedis(String key, String value, Jedis jedis) throws InterruptedException {
    // Redis3.0 需要设置一个 SetParams
    String result = jedis.set(LOCK_PREFIX + key, value, new SetParams().nx().px(DEFAULT_EXPIRE_TIME));
    if (LOCK_MSG.equals(result)) {
        jedis.close();
        return true;
    }
    Thread.sleep(DEFAULT_SLEEP_TIME);
    return false;
}
```

#### 解锁

如果直接使用`jedis.del()`方法删除锁并且`不判断锁的拥有者`，会导致任何客户端都可以随时进行解锁，即使这把锁不是它的。比如下面的例子。

> 客户端A & 客户端B
>
> A加锁 --> A准备解锁 --> 锁过期 --> B加锁成功 --> A成功解锁 --> 解的是B加的锁

由于Lua脚本在Redis中执行是原子性的，所以可以使用lua脚本解锁。

- redis会为lua脚本执行创建伪客户端模拟客户端调用redis执行命令，伪客户端执行lua脚本是排他的

```lua
if redis.call('get',KEYS[1]) == ARGV[1]
    then return redis.call('del',KEYS[1])
else 
    return 0
end
```

unlock(String key, String value)

```java
public boolean unlock(String key, String value) {
    Jedis jedis = jedisPool.getResource();

    String script = "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end";
    Object result = jedis.eval(script, Collections.singletonList(LOCK_PREFIX + key), Collections.singletonList(value));

    jedis.close();
    return UNLOCK_MSG.equals(result);
}
```

#### 测试

为了方便测试，使用一个嵌入式的Redis工具，项目地址：https://github.com/kstyrc/embedded-redis

可以参考[测试相关代码](https://github.com/AntonZhao/DistributedLock_Redis/tree/master/src/test/java/com/anton)

测试条目：

1. A线程lock，B线程lock --- lock会阻塞到B线程等待时间结束
2. A线程lock，B线程trylock --- trylock拿不到锁会直接返回
3. 其他进程释放锁定后应恢复当前任务
4. 释放自己的锁，返回解锁是否成功
5. 释放别人的锁，返回解锁是否成功

## 3. 余留问题

### 3.1 超时问题

> 如果A拿到锁之后设置了超时时长，但是业务执行的时长超过了超时时长，导致A还在执行业务但是锁已经被释放，此时其他进程就会拿到锁从而执行相同的业务，此时因为并发导致分布式锁失去了意义。

如果客户端可以通过在key快要过期的时候判断下任务有没有执行完毕，如果还没有那就自动延长过期时间，那么确实可以解决并发的问题，但是锁的超时时长也就失去了意义。所以个人认为最好的解决方式是在锁超时的时候通知服务器去停掉超时任务，但是结合上Redis的发布订阅机制不免有些过重了，而且Redis没有Zookeeper的临时节点，也没有etcd的全局序列号，所以虽然可以实现，但是并不方便，所以etcd、zookeeper则是适合这种场景的载体。

比如在etcdv3的官方[分布式锁](https://github.com/etcd-io/etcd/blob/master/clientv3/concurrency/mutex.go#L26)中的加锁是这样实现的：

1. 如果是第一次加锁，直接使用锁的key以及一个创建时全局递增的序列号来创建一个etcd上的键值对，此时等同于拿到了锁，最后返回给业务代码执行
2. 如果该锁的key已经存在，说明锁正在被别的客户端使用，那么通过watch机制开始监听该key的所有以前的版本，直到这个key的所有以前的版本被删除（相当于是按“公平锁”的方式拿锁的，避免了大量客户端同时抢锁的“惊群效应”），就等于拿到了锁，最后返回给业务代码执行

### 3.2 单点故障

集群

### 3.3 更多功能

参考[Redisson的](https://github.com/redisson/redisson/wiki/8.-分布式锁和同步器)