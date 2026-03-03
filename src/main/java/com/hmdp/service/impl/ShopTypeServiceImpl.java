package com.hmdp.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

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

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    /**
     * 查询所有商铺类型(带缓存的查询)
     *
     * @return
     */
    @Override
    public List<ShopType> queryTypeList() {
        // 1.从redis中商铺缓存
        String shopTypeJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_KEY);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(shopTypeJson)) {
            List<ShopType> shopTypes = JSONUtil.toList(shopTypeJson, ShopType.class);
            // 3.存在，返回数据
            return shopTypes;
        }
        // 4.不存在，查询数据库
        List<ShopType> shopTypes = query().orderByAsc("sort").list();
        // 5.数据库存在，写入redis
        if (shopTypes != null) {
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_TYPE_KEY, JSONUtil.toJsonStr(shopTypes));
            return shopTypes;
        }
        // 6.数据库不存在，返回null
        return null;
    }
}
