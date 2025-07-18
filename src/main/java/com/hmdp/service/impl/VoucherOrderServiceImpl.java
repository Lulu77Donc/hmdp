package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //阻塞队列，当队列里有元素时，取出元素，没有则阻塞
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    //创建线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    @PostConstruct//在当前类初始化之后运行
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    //创建线程任务
    private class VoucherOrderHandler implements Runnable{

        @Override
        public void run() {
            while (true){
                try {
                    //获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    //创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                }
            }
        }
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //获取用户
        Long userId = voucherOrder.getUserId();
        //创建锁对象,分布式锁
        //*SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);*//*
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //获取锁
        boolean isLock = lock.tryLock();
        //判断是否获取锁成功
        if(!isLock){
            //获取锁失败,返回错误或重试
            log.error("不允许重复下单");
            return;
        }
        //获得当前类对象的代理对象，注意启动类需要配置暴露动态代理对象
        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            //释放锁
            lock.unlock();
        }
    }

    private IVoucherOrderService proxy;

    /**
     * 创建秒杀订单
     *
     * @param voucherId
     * @return
     */
    //改进秒杀业务，提高并发性能，使用消息队列异步通信
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString()
        );

        //2.判断结果是为0
        int r = result.intValue();
        if(r != 0){
            //2.1.不为0，代表没有购买资格
            return Result.fail(r==1?"库存不足":"不能重复下单");
        }

        //2.2.为0，有购买资格，把下单信息保存到阻塞队列中
        VoucherOrder voucherOrder = new VoucherOrder();
        //订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //用户id
        voucherOrder.setUserId(userId);
        //代金券id
        voucherOrder.setVoucherId(voucherId);
        //创建阻塞队列
        orderTasks.add(voucherOrder);

        //提前获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        //3.返回订单id
        return Result.ok(0);
    }

   /* @Override
    public Result seckillVoucher(Long voucherId) {

        //1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            //还没开始
            return Result.fail("秒杀尚未开始");
        }
        //3.判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            //已经结束
            return Result.fail("秒杀已经结束");
        }
        //4.判断库存是否充足
        if (voucher.getStock() < 1) {
            //库存不足
            return Result.fail("库存不足");
        }

        Long userId = UserHolder.getUser().getId();
        //由于这里只需要给一样的用户加上锁，因此可以用用户唯一标识id来上锁，确保不会有第二个相同的用户进来修改，确保一人一单
        //给每一个同样用户id的用户加锁，注意每次返回的是一个新对象，因此需要intern规范化返回地址
        //这里由于在方法中需要提交事务，放在方法里面可能锁释放了，事务才提交，可能导致并发问题
        *//*synchronized (userId.toString().intern()) {
            //如果直接return调用的是this当前类的对象，而不是动态代理的对象，既会让方法内的事务失效,因为当前createVoucherOrder（）方法是写在实现类里的，spring的代理对象无法拿到这个方法
            //事务是基于AOP实现 AOP会将目标对象方法增强（在原方法前面加上开启事务 后面加上提交）生成一个新的代理对象放到bean中 所以只有代理对象有事务功能 而原对象没有
            //获得当前类对象的代理对象，注意启动类需要配置暴露动态代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }*//*

        //创建锁对象,分布式锁
        *//*SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);*//*
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //获取锁
        boolean isLock = lock.tryLock();
        //判断是否获取锁成功
        if(!isLock){
            //获取锁失败,返回错误或重试
            return Result.fail("一个人只允许下一单");
        }
        //获得当前类对象的代理对象，注意启动类需要配置暴露动态代理对象
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            //释放锁
            lock.unlock();
        }
    }*/

    /*@Transactional
    public Result createVoucherOrder(Long voucherId) {
        //一人一单
        //查询订单
        Long userId = UserHolder.getUser().getId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        //判断订单是否存在
        if (count > 0) {
            //用户已经购买过了
            return Result.fail("用户已经购买过一次");
        }

        //5.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock=stock-1")
                .eq("voucher_id", voucherId).gt("stock", 0)//where id = ? and stock > 0
                .update();
        if (!success) {
            //扣减失败
            return Result.fail("库存不足");
        }


        //6.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //用户id
        voucherOrder.setUserId(userId);

        //代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        //7.返回订单id
        return Result.ok(orderId);
    }*/

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //一人一单
        //查询订单
        Long userId = voucherOrder.getUserId();
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        //判断订单是否存在
        if (count > 0) {
            //用户已经购买过了
            log.error("用户已经购买过一次！");
            return;
        }

        //5.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock=stock-1")
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0)//where id = ? and stock > 0
                .update();
        if (!success) {
            //扣减失败
            log.error("库存不足！");
            return;
        }

        //创建订单
        save(voucherOrder);

    }
}
