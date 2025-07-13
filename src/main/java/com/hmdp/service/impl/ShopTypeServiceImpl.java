package com.hmdp.service.impl;

import cn.hutool.core.collection.ListUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ObjectMapper objectMapper; // Jackson 序列化工具

    /**
     * 从缓存查找商铺分类
     * @return
     */
    @Override
    public Result queryTypeList() {
        try {
            // 1. 从 Redis 查询缓存
            String json = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_TYPE_KEY);
            if (json != null && !json.isEmpty()) {
                // 2. 命中缓存，反序列化
                List<ShopType> typeList = objectMapper.readValue(json,
                        new TypeReference<List<ShopType>>() {});//在反序列化时保留泛型信息，让 Jackson 知道目标类型中泛型的具体类型。
                return Result.ok(typeList);
            }

            // 3. 缓存未命中，从数据库中查询
            List<ShopType> typeList = query().orderByAsc("sort").list();
            if (typeList == null || typeList.isEmpty()) {
                // 缓存空值避免穿透
                stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_TYPE_KEY, "", 5, TimeUnit.MINUTES);
                return Result.ok(Collections.emptyList());
            }

            // 4. 写入缓存（序列化为 JSON）
            String resultJson = objectMapper.writeValueAsString(typeList);
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_TYPE_KEY, resultJson, 30, TimeUnit.MINUTES);

            return Result.ok(typeList);
        } catch (Exception e) {
            e.printStackTrace();
            return Result.fail("服务异常");
        }
    }
}
