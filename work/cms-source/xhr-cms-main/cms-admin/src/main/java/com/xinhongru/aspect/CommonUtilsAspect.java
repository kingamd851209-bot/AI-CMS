package com.xinhongru.aspect;

import lombok.extern.slf4j.Slf4j;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

/**
 * @Description @ClassName CommonUtilsAspect @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date 2022/9/4
 * 14:35
 */
@Slf4j
@Aspect
@Component
public class CommonUtilsAspect {
    @Pointcut("execution(public * com.xinhongru.web.utils.CommonUtils.*(..))")
    public void webLog() {}

    @Around(value = "webLog()")
    public Object around(ProceedingJoinPoint proceedingJoinPoint) throws Throwable {
        long beginTime = System.currentTimeMillis();
        Signature signature = proceedingJoinPoint.getSignature();
        Object proceed = proceedingJoinPoint.proceed();
        long endTime = System.currentTimeMillis();
        long runTime = endTime - beginTime;
        // 只记录耗时大于1秒的方法
        if (runTime > 1000) {
            log.info("方法:{},执行耗时{}毫秒", signature.getName(), runTime);
        }

        return proceed;
    }
}
