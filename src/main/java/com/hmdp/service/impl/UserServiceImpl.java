package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result sendCode(String phone, HttpSession session) {
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("号码有误");
        }
        //生成验证码并保存到session
        String code = RandomUtil.randomNumbers(6);
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //模拟发送
        log.debug("发送的验证码为："+code);

        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //先判断一下号码的正确性
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("号码有误");
        }
        //再判断验证码是否和发送的一致,基于redis
        String realCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String userCode = loginForm.getCode();
        if(userCode == null || !userCode.equals(realCode)){
            return Result.fail("验证码有误");
        }
        //一致，应该判断用户是否登陆过，登陆过就将用户信息保存到session，没有就创建新用户
        User user = query().eq("phone", phone).one();
        if(user == null){
            user =  createUserWithPhone(phone);
        }
        //这里是将userDTO对象存在session，但现在应该存在redis
        //session.setAttribute("user", BeanUtil.copyProperties(user,UserDTO.class));
        //过滤敏感信息
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //转为map进行存储
        Map<String, Object> stringObjectMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true).
                        setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        //存储
        String tokenKey = UUID.randomUUID().toString();
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY+tokenKey, stringObjectMap);
        stringRedisTemplate.expire(LOGIN_USER_KEY+tokenKey, LOGIN_USER_TTL, TimeUnit.SECONDS);
        return Result.ok(tokenKey);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME + RandomUtil.randomString(6));
        save(user);
        return user;
    }
}
