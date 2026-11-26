package com.xinhongru.utils;

import com.alibaba.fastjson.JSON;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * @Description redis 操作工具类 @ClassName RedisUtils @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date
 * 2021/12/5 14:47
 */
@Slf4j
@Component
public class RedisUtils {
    private static RedisTemplate redisTemplate;

    @Autowired
    public void setRedisTemplate(RedisTemplate redisTemplate) {
        RedisUtils.redisTemplate = redisTemplate;
    }

    /** 私有化构造函数 */
    private RedisUtils() {
        redisTemplate = new RedisTemplate();
    }

    private static final RedisUtils REDIS_UTILS = new RedisUtils();

    /**
     * 对外输出单例对象
     *
     * @return
     */
    public static RedisUtils getInstance() {
        return REDIS_UTILS;
    }

    /**
     * 获取原生的RedisTemplate对象
     *
     * @return
     */
    public static RedisTemplate getRedisTemplate() {
        return redisTemplate;
    }

    /**
     * 删除单个key
     *
     * @param key key
     */
    public static void delete(String key) {
        redisTemplate.delete(key);
    }

    /**
     * 删除多个key
     *
     * @param keys keys
     */
    public static void deleteKeys(String... keys) {
        redisTemplate.delete(keys);
    }

    /**
     * 设置key的失效时间
     *
     * @param key key
     * @param time 当前时间+时间 单位分钟
     */
    public static void setExpire(String key, Long time) {
        setExpire(key, time, TimeUnit.MINUTES);
    }

    /**
     * 设置key的失效时间
     *
     * @param key key
     * @param time 过期时间
     * @param timeUnit 时间单位
     */
    public static void setExpire(String key, Long time, TimeUnit timeUnit) {
        redisTemplate.expire(key, time, timeUnit);
    }

    /**
     * 获取key的失效时间
     *
     * @param key key
     * @return
     */
    public static Long getExpire(String key) {
        Long expire = redisTemplate.getExpire(key);
        return expire;
    }

    /**
     * 判断key是否存在
     *
     * @param key key
     * @return
     */
    public static boolean hasKey(String key) {
        return redisTemplate.hasKey(key);
    }

    /**
     * 添加字符串对象到redis
     *
     * @param key key
     * @param value 字符串值
     * @param expire 过期时间
     * @param timeUnit 时间单位
     */
    public static void setString(String key, String value, Long expire, TimeUnit timeUnit) {
        redisTemplate.boundValueOps(key).set(value, expire, timeUnit);
    }

    /**
     * 添加字符串对象到redis
     *
     * @param key key
     * @param value 字符串值
     * @param expire 过期时间 单位分钟
     */
    public static void setString(String key, String value, Long expire) {
        set(key, value, expire, TimeUnit.MINUTES);
    }

    /**
     * 添加字符串对象到redis 永不过期
     *
     * @param key 字符串值
     * @param value 过期时间 单位分钟
     */
    public static void setString(String key, String value) {
        redisTemplate.boundValueOps(key).set(value);
    }

    /**
     * 通过key获取字符串对象
     *
     * @param key
     * @return
     */
    public static String getString(String key) {
        return (String) redisTemplate.boundValueOps(key).get();
    }

    public static Integer getInteger(String key) {
        return (Integer) redisTemplate.boundValueOps(key).get();
    }

    /**
     * 获取对象 并转换为实体对象
     *
     * @param key
     * @param clazz
     * @param <T>
     * @return
     */
    public static <T> T getEntity(String key, Class<T> clazz) {
        return JSON.toJavaObject((JSON) redisTemplate.boundValueOps(key).get(), clazz);
    }

    /**
     * 获取hash对象 并转换为实体对象
     *
     * @param hashKey
     * @param smallKey
     * @param clazz
     * @param <T>
     * @return
     */
    public static <T> T getHashEntity(String hashKey, String smallKey, Class<T> clazz) {
        return JSON.toJavaObject((JSON) getHashSmallValue(hashKey, smallKey), clazz);
    }

    /**
     * 给hash对象添加子对象
     *
     * @param hashKey hashKey
     * @param smallKey 子对象Key
     * @param smallValue 子对象值
     */
    public static <T> void setHash(String hashKey, String smallKey, T smallValue) {
        redisTemplate.boundHashOps(hashKey).put(smallKey, smallValue);
    }

    /**
     * 批量给hash对象添加子对象
     *
     * @param hashKey hashKey
     * @param map 子对象集合
     * @param <T> 对象类型
     */
    public static <T> void setHash(String hashKey, Map<String, T> map) {
        redisTemplate.boundHashOps(hashKey).putAll(map);
    }

    /**
     * 批量给hash对象添加子对象
     *
     * @param hashKey hashKey
     * @param map 子对象集合
     * @param <T> 对象类型
     */
    public static <T> void setHashLongMap(String hashKey, Map<Long, T> map) {
        HashMap<String, Object> stringObjectHashMap = new HashMap<>();
        for (Map.Entry<Long, T> longTEntry : map.entrySet()) {
            stringObjectHashMap.put(longTEntry.getKey().toString(), longTEntry.getValue());
        }
        setHash(hashKey, stringObjectHashMap);
    }

    /**
     * 批量给hash对象添加子对象,并且清除已存在hashKey
     *
     * @param hashKey hashKey
     * @param map 子对象集合
     * @param <T> 对象类型
     */
    public static <T> void setHashLongMapClean(String hashKey, Map<Long, T> map) {
        HashMap<String, Object> stringObjectHashMap = new HashMap<>();
        // 判断hashKey是否存在，存在则清除
        if (RedisUtils.hasKey(hashKey)) {
            RedisUtils.delete(hashKey);
        }
        for (Map.Entry<Long, T> longTEntry : map.entrySet()) {
            stringObjectHashMap.put(longTEntry.getKey().toString(), longTEntry.getValue());
        }
        setHash(hashKey, stringObjectHashMap);
    }

    /**
     * 获取hash对象所有的子对象的key
     *
     * @param hashKey hashKey
     * @return 所有的子对象的key
     */
    public static Set getHashSmallKeys(String hashKey) {
        return redisTemplate.boundHashOps(hashKey).keys();
    }

    /**
     * 获取hash对象所有的子对象的值
     *
     * @param hashKey hashKey
     * @return
     */
    public static List getHashSmallValues(String hashKey) {
        return redisTemplate.boundHashOps(hashKey).values();
    }

    /**
     * 获取hash对象特定的子对象的值
     *
     * @param hashKey hashKey
     * @param smallKey 子对象Key
     * @return
     */
    public static Object getHashSmallValue(String hashKey, String smallKey) {
        return redisTemplate.boundHashOps(hashKey).get(smallKey);
    }

    /**
     * 获取hash对象的所有子对象
     *
     * @param hashKey hashKey
     * @return
     */
    public static Map<String, Object> getHashEntries(String hashKey) {
        return redisTemplate.boundHashOps(hashKey).entries();
    }

    /**
     * 删除hash对象中的子对象
     *
     * @param hashKey hashKey
     * @param smallKey 子对象Key
     */
    public static void deleteHashSmallKey(String hashKey, String smallKey) {
        redisTemplate.boundHashOps(hashKey).delete(smallKey);
    }

    /**
     * 批量删除hash对象中的子对象
     *
     * @param hashKey hashKey
     * @param smallKey 子对象Key
     */
    public static void deleteHashSmallKeys(String hashKey, String... smallKey) {
        redisTemplate.boundHashOps(hashKey).delete((Object[]) smallKey);
    }

    /**
     * 检查hash对象中是否包含子对象Key
     *
     * @param hashKey hashKey
     * @param smallKey 子对象Key
     * @return
     */
    public static Boolean hasHashSmallKey(String hashKey, String smallKey) {
        return redisTemplate.boundHashOps(hashKey).hasKey(smallKey);
    }

    /**
     * 添加对象到Set中
     *
     * @param setKey setKey
     * @param members 集合对象
     * @param <T>
     */
    public static <T> void setSetMembers(String setKey, T... members) {
        redisTemplate.boundSetOps(setKey).add(members);
    }

    /**
     * 根据setKey获取所有对象
     *
     * @param setKey
     * @return
     */
    public static Set getSetMembers(String setKey) {
        return redisTemplate.boundSetOps(setKey).members();
    }

    /**
     * 检查Set对象中是否包含特定的member对象
     *
     * @param setKey
     * @param member
     * @param <T>
     * @return
     */
    public static <T> Boolean hasSetMember(String setKey, T member) {
        return redisTemplate.boundSetOps(setKey).isMember(member);
    }

    /**
     * 根据setKey获取Set的长度
     *
     * @param setKey setKey
     * @return Set的长度
     */
    public static Long getSetSize(String setKey) {
        return redisTemplate.boundSetOps(setKey).size();
    }

    /**
     * 从Set中移除特定的对象
     *
     * @param setKey setKey
     * @param members members对象集合
     * @param <T>
     * @return 移除的记录数
     */
    public static <T> Long deleteSetMember(String setKey, T... members) {
        return redisTemplate.boundSetOps(setKey).remove(members);
    }

    /**
     * 添加对象到集合左侧
     *
     * @param listKey listKey
     * @param value 对象值
     * @param <T>
     */
    public static <T> void setListLeftPush(String listKey, T value) {
        redisTemplate.boundListOps(listKey).leftPush(value);
    }

    /**
     * 添加对象集合到集合左侧
     *
     * @param listKey
     * @param values
     * @param <T>
     */
    public static <T> void setListLeftPushAll(String listKey, T... values) {
        redisTemplate.boundListOps(listKey).leftPushAll(values);
    }

    /**
     * 添加对象到集合右侧
     *
     * @param listKey listKey
     * @param value 对象值
     * @param <T>
     */
    public static <T> void setListRightPush(String listKey, T value) {
        redisTemplate.boundListOps(listKey).rightPush(value);
    }

    /**
     * 添加对象集合到集合右侧
     *
     * @param listKey listKey
     * @param values values
     * @param <T>
     */
    public static <T> void setListRightPushAll(String listKey, T... values) {
        redisTemplate.boundListOps(listKey).rightPushAll(values);
    }

    /**
     * 获取List集合中特定位置的对象集合
     *
     * @param listKey listKey
     * @param begin 开始索引
     * @param end 结束索引
     * @return 返回对象集合
     */
    public static List getListRang(String listKey, Long begin, Long end) {
        return redisTemplate.boundListOps(listKey).range(begin, end);
    }

    /**
     * 删除集合中的第一个记录,并将其返回
     *
     * @param listKey listKey
     * @return
     */
    public static Object deleteListFirst(String listKey) {
        return redisTemplate.boundListOps(listKey).leftPop();
    }

    /**
     * 删除集合中的最后一个记录,并将其返回
     *
     * @param listKey listKey
     * @return
     */
    public static Object deleteListLast(String listKey) {
        return redisTemplate.boundListOps(listKey).rightPop();
    }

    /**
     * 获取List集合中特定索引的对象
     *
     * @param listKey listKey
     * @param index 索引
     * @return 对象
     */
    public static Object getListItemByIndex(String listKey, Long index) {
        return redisTemplate.boundListOps(listKey).index(index);
    }

    /**
     * 获取list集合长度
     *
     * @param listKey listKey
     * @return 长度
     */
    public static Long getListSize(String listKey) {
        return redisTemplate.boundListOps(listKey).size();
    }

    /**
     * 根据索引更新List特定位置的对象
     *
     * @param listKey listKey
     * @param index 索引
     * @param value 对象值
     * @param <T>
     */
    public static <T> void updateListByIndex(String listKey, Long index, T value) {
        redisTemplate.boundListOps(listKey).set(index, value);
    }

    /**
     * 通用添加对象
     *
     * @param key 键
     * @param value 值
     * @param expire 有效期
     * @param timeUnit 时间单位
     * @param <T>
     */
    public static <T> void set(String key, T value, Long expire, TimeUnit timeUnit) {
        redisTemplate.boundValueOps(key).set(value, expire, timeUnit);
    }

    /**
     * 通用添加对象
     *
     * @param key 键
     * @param value 值
     * @param expire 有效期 时间单位分钟
     * @param <T>
     */
    public static <T> void set(String key, T value, Long expire) {
        set(key, value, expire, TimeUnit.MINUTES);
    }

    /**
     * 通用添加对象 有效期一小时
     *
     * @param key
     * @param value
     * @param <T>
     */
    public static <T> void setExpire1Hour(String key, T value) {
        set(key, value, 60L, TimeUnit.MINUTES);
    }

    /**
     * 通用添加对象 有效期30分钟
     *
     * @param key
     * @param value
     * @param <T>
     */
    public static <T> void setExpire30MINUTES(String key, T value) {
        set(key, value, 30L, TimeUnit.MINUTES);
    }

    /**
     * 通用添加对象 有效期20分钟
     *
     * @param key
     * @param value
     * @param <T>
     */
    public static <T> void setExpire20MINUTES(String key, T value) {
        set(key, value, 20L, TimeUnit.MINUTES);
    }

    /**
     * 通用添加对象 有效期10分钟
     *
     * @param key
     * @param value
     * @param <T>
     */
    public static <T> void setExpire10MINUTES(String key, T value) {
        set(key, value, 10L, TimeUnit.MINUTES);
    }

    /**
     * 通用添加对象 有效期2分钟
     *
     * @param key
     * @param value
     * @param <T>
     */
    public static <T> void setExpire2MINUTES(String key, T value) {
        set(key, value, 2L, TimeUnit.MINUTES);
    }

    /**
     * 通用添加对象 有效期1分钟
     *
     * @param key
     * @param value
     * @param <T>
     */
    public static <T> void setExpire1MINUTES(String key, T value) {
        set(key, value, 1L, TimeUnit.MINUTES);
    }

    /**
     * 通用添加对象 有效期两小时
     *
     * @param key
     * @param value
     * @param <T>
     */
    public static <T> void setExpire2Hour(String key, T value) {
        set(key, value, 2L, TimeUnit.HOURS);
    }

    /**
     * 通用添加对象 有效期一天
     *
     * @param key
     * @param value
     * @param <T>
     */
    public static <T> void setExpire1Day(String key, T value) {
        set(key, value, 1L, TimeUnit.DAYS);
    }

    /**
     * 通用添加对象 有效期一周
     *
     * @param key
     * @param value
     * @param <T>
     */
    public static <T> void setExpire7Day(String key, T value) {
        set(key, value, 7L, TimeUnit.DAYS);
    }

    /**
     * 通用添加对象 有效期一周
     *
     * @param key
     * @param value
     * @param <T>
     */
    public static <T> void setExpire14Day(String key, T value) {
        set(key, value, 14L, TimeUnit.DAYS);
    }

    /**
     * 通用添加对象 永不过期
     *
     * @param key 键
     * @param value 值
     * @param <T>
     */
    public static <T> void set(String key, T value) {
        redisTemplate.boundValueOps(key).set(value);
    }

    /**
     * 获得缓存的基本对象。
     *
     * @param key 缓存键值
     * @return 缓存键值对应的数据
     */
    public static <T> T getCacheObject(final String key) {
        ValueOperations<String, T> operation = redisTemplate.opsForValue();
        return operation.get(key);
    }
}
