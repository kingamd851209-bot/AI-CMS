package com.xinhongru.service.imageCode.impl;

import com.wf.captcha.SpecCaptcha;
import com.xinhongru.constant.WebConst;
import com.xinhongru.enums.ErrorCodeEnum;
import com.xinhongru.requestDto.imageCodeDto.ImageCodeDto;
import com.xinhongru.responseVo.ResultVO;
import com.xinhongru.responseVo.imageCode.ImageCodeVO;
import com.xinhongru.service.imageCode.ImageCodeService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * @Description 图形验证码服务 @ClassName ImageCodeServiceImpl @Author 永远的神 @Version V1.0.0 @Since
 * 1.0 @Date 2021/12/6 14:22
 */
@Service
public class ImageCodeServiceImpl implements ImageCodeService {
    private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final DefaultRedisScript<Long> CONSUME = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);
    private final StringRedisTemplate redis;

    public ImageCodeServiceImpl(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public ResultVO<ImageCodeVO> getImageCode(Purpose purpose, ImageCodeDto imageCodeDto) {
        SpecCaptcha specCaptcha =
                new SpecCaptcha(
                        imageCodeDto.getWidth(),
                        imageCodeDto.getHeight(),
                        imageCodeDto.getLength());
        // specCaptcha.setCharType(2);
        String codeKey = UUID.randomUUID().toString();
        String codeValue = specCaptcha.text().toLowerCase(Locale.ROOT);
        redis.opsForValue().set(redisKey(purpose, codeKey), codeValue, Duration.ofMinutes(WebConst.IMAGE_CODE_EXPIRE));

        ImageCodeVO imageCode = new ImageCodeVO();
        imageCode.setCodeKey(codeKey).setBase64Image(specCaptcha.toBase64());
        return ResultVO.success(imageCode);
    }

    @Override
    public ResultVO<Boolean> checkImageCode(Purpose purpose, String codeKey, String codeValue) {
        if (codeKey == null || !TOKEN.matcher(codeKey).matches() || codeValue == null
                || codeValue.isBlank() || codeValue.length() > 16) {
            return ResultVO.fail(ErrorCodeEnum.IMAGE_CODE_ERROR);
        }
        Long consumed = redis.execute(CONSUME, List.of(redisKey(purpose, codeKey)),
                codeValue.toLowerCase(Locale.ROOT));
        return Long.valueOf(1).equals(consumed) ? ResultVO.success() : ResultVO.fail(ErrorCodeEnum.IMAGE_CODE_ERROR);
    }

    private static String redisKey(Purpose purpose, String token) {
        return WebConst.IMAGE_CODE_PREFIX + purpose.name() + ":" + token;
    }
}
