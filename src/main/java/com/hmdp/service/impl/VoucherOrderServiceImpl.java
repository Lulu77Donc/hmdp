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
import org.springframework.aop.framework.AopContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 创建秒杀订单
     *
     * @param voucherId
     * @return
     */
    @Override
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
        /*synchronized (userId.toString().intern()) {
            //如果直接return调用的是this当前类的对象，而不是动态代理的对象，既会让方法内的事务失效,因为当前createVoucherOrder（）方法是写在实现类里的，spring的代理对象无法拿到这个方法
            //事务是基于AOP实现 AOP会将目标对象方法增强（在原方法前面加上开启事务 后面加上提交）生成一个新的代理对象放到bean中 所以只有代理对象有事务功能 而原对象没有
            //获得当前类对象的代理对象，注意启动类需要配置暴露动态代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }*/

        //创建锁对象,分布式锁
        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        //获取锁
        boolean isLock = lock.tryLock(5);
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
    }

    @Transactional
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
    }
}
