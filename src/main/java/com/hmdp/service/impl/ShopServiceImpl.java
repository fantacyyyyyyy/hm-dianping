package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryById(Long id) {
//        注释的是调用缓存穿透
//        Shop result = queryWithPassThrough(id);
//        if (result == null || StrUtil.isBlank(result.getName())) {
//            return Result.fail("店铺不存在");
//        }else{
//            return Result.ok(result);
//        }

        //下面是解决缓存击穿
        Shop shop = queryWithMutex(id);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);

        //下面是利用逻辑过期解决缓存穿透
//        Shop shop = queryWithLogicalExpire(id);
//        if (shop == null) {
//            return Result.fail("店铺不存在");
//        }
//        return Result.ok(shop);
    }

    /**
     * 缓存穿透
     * @param id
     * @return
     */
    private Shop queryWithPassThrough(Long id) {
        //根据id先查缓存，没有再查数据库，下面代码加了缓存穿透的解决
        String shop = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        if (StrUtil.isNotBlank(shop)) {
            return JSONUtil.toBean(shop, Shop.class);
        }
        if (shop != null){
            return null;
        }
        //查数据库
        Shop shop1 = baseMapper.selectById(id);
        //查数据库为空，在缓存中加""
        if (shop1 == null) {
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        //查到了，就把正确数据加入缓存
        String shop2 = JSONUtil.toJsonStr(shop1);
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, shop2,CACHE_SHOP_TTL,TimeUnit.MINUTES);
        return shop1;

    }

    /**
     * 解决缓存击穿问题，里面包含了缓存穿透
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id)  {
        // 1、从redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        // 2、判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 存在,直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断命中的值是否是空值
        if (shopJson != null){
            return null;
        }
        // 4.实现缓存重构
        //4.1 获取互斥锁
        String lockKey = "lock:shop:" + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            // 4.2 判断否获取成功
            if(!isLock){
                //4.3 失败，则休眠重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //4.4 成功，根据id查询数据库
            //4.4.1再次查缓存
            String retryCheckRedis = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
            if (StrUtil.isNotBlank(retryCheckRedis)) {
                return JSONUtil.toBean(retryCheckRedis, Shop.class); // 缓存已存在，直接返回，不查库
            }
            if (retryCheckRedis != null) {
                return null; // 命中空值缓存，直接返回
            }
            //还是不命中，那就查数据库
            shop = getById(id);
            // 5.不存在，返回错误
            if(shop == null){
                //将空值写入redis
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                //返回错误信息
                return null;
            }
            //6.写入redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(shop),CACHE_NULL_TTL,TimeUnit.MINUTES);

        }catch (Exception e){
            throw new RuntimeException(e);
        }
        finally {
            //7.释放互斥锁
            unlock(lockKey);
        }
        return shop;
    }

    /**
     * 利用逻辑过期解决缓存穿透
     *
     *
     */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    private Shop queryWithLogicalExpire(Long id) {
        //根据id先查缓存，没有再查数据库，下面代码加了缓存穿透的解决
        String shop = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        if (StrUtil.isBlank(shop)) {
            return null;
        }
        //查到的是String需要进行转换
        RedisData rd = JSONUtil.toBean(shop, RedisData.class);
        // 正确写法：先转为字符串，再解析成 Shop 对象
        String dataStr = JSONUtil.toJsonStr(rd.getData()); // 无论data是什么类型，先转字符串
        Shop rshop = JSONUtil.toBean(dataStr, Shop.class); // 从字符串解析为Shop

        LocalDateTime expireTime = rd.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            return rshop;
        }

        boolean b = tryLock(CACHE_SHOP_KEY + id);
        if (b) {
            //二次查缓存，看是否过期
            String json = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
            if (StrUtil.isBlank(json)) {
                return null;
            }
            RedisData redisData = JSONUtil.toBean(json, RedisData.class);
            // 正确写法：先转为字符串，再解析成 Shop 对象
            String dataStr2 = JSONUtil.toJsonStr(redisData.getData()); // 无论data是什么类型，先转字符串
            Shop rrshop = JSONUtil.toBean(dataStr2, Shop.class); // 从字符串解析为Shop

            LocalDateTime expireTime2 = redisData.getExpireTime();
            // 5.判断是否过期
            if(expireTime2.isAfter(LocalDateTime.now())) {
                // 5.1.未过期，直接返回店铺信息
                return rrshop;
            }

            //还是过期状态，那就启动另一个线程修改缓存中的数据
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try{
                    //重建缓存
                    this.saveShop2Redis(id,20L);
                }catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    unlock(CACHE_SHOP_KEY + id);
                }
            });
        }

        return rshop;

    }

    /**
     * 加互斥锁的方法
     * @param key
     * @return
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 解锁的方法
     * @param key
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }

    /**
     * 利用单元测试进行缓存预热，可以在测试的时候把缓存提前加进去，用于逻辑过期解决缓存穿透
     * @param
     * @return
     */
    public void saveShop2Redis(Long id, Long expiredTime) {
        Shop shop = getById(id);
        RedisData rd = new RedisData();
        rd.setData(JSONUtil.toJsonStr(shop));
        rd.setExpireTime(LocalDateTime.now().plusSeconds(expiredTime));
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id, JSONUtil.toJsonStr(rd));
    }

    @Override
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("商铺不存在");
        }
        //先更新数据库，再删除缓存
        updateById(shop);
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }
}
