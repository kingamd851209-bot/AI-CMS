package com.xinhongru.aspect;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SimplePropertyPreFilter;
import com.xinhongru.annotation.SystemLog;
import com.xinhongru.common.reflect.EntityFields;
import com.xinhongru.constant.WebConst;
import com.xinhongru.entity.ContentEntity;
import com.xinhongru.service.member.impl.MemberCredentials;
import com.xinhongru.entity.LogEntity;
import com.xinhongru.entity.OneshotEntity;
import com.xinhongru.entity.UserEntity;
import com.xinhongru.responseVo.ResultVO;
import com.xinhongru.service.log.LogService;
import com.xinhongru.service.oneshot.OneShotService;
import com.xinhongru.utils.AuthorityUtils;
import com.xinhongru.utils.RequestOriginUtils;
import com.xinhongru.utils.SensitiveWordUtils;
import com.xinhongru.utils.TaleUtils;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.extern.slf4j.Slf4j;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.validation.BindingResult;
import org.springframework.validation.support.BindingAwareModelMap;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/** 请求的日志处理 Created at 2021/4/28. */
@Aspect
@Component
@Slf4j
public class WebLogAspect {
    private LogService logService;
    private OneShotService oneShotService;

    @Autowired
    public void setOneShotService(OneShotService oneShotService) {
        this.oneShotService = oneShotService;
    }

    @Autowired
    public void setLogService(LogService logService) {
        this.logService = logService;
    }

    private final ThreadLocal<Deque<AuditFrame>> auditFrames = new ThreadLocal<>();

    private record AuditFrame(LogEntity entry, long startedAt) {}

    private AuditFrame currentAudit() {
        Deque<AuditFrame> frames = auditFrames.get();
        return frames == null ? null : frames.peek();
    }

    private LogEntity currentLog() {
        return currentAudit().entry();
    }

    @Pointcut("execution(public * com.xinhongru.controller..*.*(..))")
    public void webLog() {}

    /**
     * 前置通知
     *
     * @param joinPoint 切点
     */
    @Around("webLog()")
    public Object doBefore(ProceedingJoinPoint joinPoint) {
        Deque<AuditFrame> frames = auditFrames.get();
        if (frames == null) {
            frames = new ArrayDeque<>();
            auditFrames.set(frames);
        }
        AuditFrame frame = new AuditFrame(new LogEntity(), System.currentTimeMillis());
        frames.push(frame);
        Object returned = null;
        try {
            returned = invokeLogged(joinPoint, frame);
            return returned;
        } catch (RuntimeException error) {
            frame.entry().setResponseData("FAILED");
            throw error;
        } finally {
            // AfterReturning/AfterThrowing可能已在proceed内部完成；只清理本次仍持有的帧。
            if (currentAudit() == frame) finishAudit(false, returnedStatus(returned));
        }
    }

    private Object invokeLogged(ProceedingJoinPoint joinPoint, AuditFrame frame) {
        log.info(
                "进入方法：{}.{}",
                joinPoint.getSignature().getDeclaringTypeName(),
                joinPoint.getSignature().getName());
        // 接收到请求，记录请求内容
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        assert attributes != null;
        HttpServletRequest request = attributes.getRequest();
        HttpServletResponse response = attributes.getResponse();
        //        HttpSession session = request.getSession();
        // 如果是POST请求则校验请求入参中是否包含敏感词
        if (request.getMethod().equals(WebConst.REQUEST_METHOD_POST)) {
            log.info("次请求为POST请求");
            if (joinPoint.getArgs().length > 0) {
                log.info("请求参数长度为：" + joinPoint.getArgs().length);
                Object[] args = joinPoint.getArgs();
                OneshotEntity modelById = oneShotService.getModelById(14);
                if (modelById != null && StringUtils.isNotBlank(modelById.getNote1())) {
                    log.info("进入敏感词过滤...");
                    // 读取敏感词
                    List<String> sensitiveList = Arrays.asList(modelById.getNote1().split("\\|"));
                    // 设置敏感词
                    SensitiveWordUtils.addSensitiveWord(sensitiveList);
                    for (int i = 0; i < args.length; i++) {
                        // 请求参数类型判断过滤，防止JSON转换报错
                        if (args[i] instanceof HttpServletRequest
                                || args[i] instanceof HttpServletResponse
                                || args[i] instanceof MultipartFile
                                || args[i] instanceof BindingResult
                                || args[i] instanceof BindingAwareModelMap) {
                            log.info("不符合敏感词过滤规则的参数");
                            continue;
                        }
                        // 过滤敏感词库自身设置链接
                        if (request.getRequestURI()
                                        .equalsIgnoreCase(WebConst.SENSITIVE_WORD_SETTING_URL)
                                && args[i] instanceof OneshotEntity) {
                            OneshotEntity oneshotEntity = (OneshotEntity) args[i];
                            if (oneshotEntity.getId().equals(14)) {
                                log.info("进入敏感词设置页面，过滤敏感词库自身设置链接,跳过敏感词过滤校验");
                                break;
                            }
                        }
                        String scanText;
                        if (args[i] instanceof ContentEntity content
                                && MemberCredentials.isDirectory(content.getClassid())) {
                            SimplePropertyPreFilter fields = new SimplePropertyPreFilter(ContentEntity.class);
                            fields.getExcludes().add("singleLine5");
                            scanText = JSON.toJSONString(content, fields);
                        } else {
                            scanText = JSON.toJSONString(args[i]);
                        }
                        List<String> sensitiveWord = SensitiveWordUtils.getSensitiveWord(scanText, 1);
                        if (CollectionUtils.isNotEmpty(sensitiveWord)) {
                            log.info("敏感词库存在敏感词，开始过滤...");
                            // 包含敏感词
                            String errorMsg =
                                    "请求内容中包含敏感字符：【"
                                            + StringUtils.join(sensitiveWord, ',')
                                            + "】系统拒绝了您的请求。";
                            String jsonString = JSON.toJSONString(ResultVO.fail(errorMsg));
                            try {
                                response.setContentType(WebConst.CONTENT_TYPE_APPLICATION_JSON);
                                // 由于response.getWriter()会报has already been called for this
                                // response异常
                                // 所以改写为response.getOutputStream方式输出内容
                                ServletOutputStream outputStream = response.getOutputStream();
                                outputStream.write(jsonString.getBytes(StandardCharsets.UTF_8));
                                outputStream.flush();
                                outputStream.close();
                                log.info("敏感词过滤结束");
                                return null;
                            } catch (IOException e) {
                                log.warn("敏感词过滤响应写入失败");
                                return null;
                            }
                        }
                    }
                }
            }
        }
        log.info("进入日志记录切面");

        if (checkAuthorityAndBuilderLogEntity(joinPoint, request, response)) {
            return null;
        }

        // 记录下请求内容
        log.info("URL : " + request.getRequestURI());
        log.info("HTTP_METHOD : " + request.getMethod());
        log.info("IP : " + RequestOriginUtils.clientIp(request));
        log.info(
                "CLASS_METHOD : "
                        + joinPoint.getSignature().getDeclaringTypeName()
                        + "."
                        + joinPoint.getSignature().getName());

        Object proceed = null;
        try {
            log.info("开始执行请求方法");
            proceed = joinPoint.proceed();
            log.info("方法的执行完毕");
        } catch (Throwable throwable) {
            log.warn("请求方法执行失败");
            frame.entry().setResponseData("FAILED");
        }
        return proceed;
    }

    /**
     * 根据日志注解验证权限 并组装日志对象
     *
     * @param joinPoint 切点
     * @param request
     * @param response
     * @return 是否校验成功
     */
    private boolean checkAuthorityAndBuilderLogEntity(
            JoinPoint joinPoint, HttpServletRequest request, HttpServletResponse response) {
        // 获取方法签名
        MethodSignature methodSignature = (MethodSignature) joinPoint.getSignature();
        // 获取方法对象
        Method method = methodSignature.getMethod();
        // 获取类名
        String className = joinPoint.getTarget().getClass().getName();
        // 获取方法名
        String methodName = method.getName();
        // 获取系统日志注解
        SystemLog systemLog = method.getAnnotation(SystemLog.class);
        if (systemLog != null) {
            // 栏目ID
            int classId = systemLog.classId();
            // 栏目ID集合
            int[] classIds = systemLog.classIds();
            // 操作描述
            String actionName = systemLog.value();
            // classId参数名
            String classIdParamName = systemLog.classIdParamName();
            if (classId != 0) {
                // 栏目权限判断
                String responseResult =
                        AuthorityUtils.checkAuthorityForView(classId, request, response);
                if (responseResult != null) {
                    return true;
                }
            } else if (ArrayUtils.isNotEmpty(classIds)) {
                // 栏目权限判断
                String responseResult =
                        AuthorityUtils.checkAuthorityForView(classIds, request, response);
                if (responseResult != null) {
                    return true;
                }
            } else if (StringUtils.isNotBlank(classIdParamName)) {
                Object[] parameterValues = joinPoint.getArgs();
                String[] parameterNames = methodSignature.getParameterNames();
                // 参数是否包含. 如果包含.则表示读取当前子类的属性
                boolean containsPoint = classIdParamName.contains(".");
                if (!containsPoint) {
                    for (int i = 0; i < parameterNames.length; i++) {
                        if (parameterNames[i].equals(classIdParamName)) {
                            classId = (int) parameterValues[i];
                            break;
                        }
                    }
                } else {
                    String[] splitArr = classIdParamName.split("\\.");
                    for (int j = 0; j < parameterValues.length; j++) {
                        if (parameterNames[j].equalsIgnoreCase(splitArr[0])) {
                            Object obj = parameterValues[j];
                            if (obj instanceof OneshotEntity) {
                                OneshotEntity oneshotEntity = (OneshotEntity) obj;
                                try {
                                    Field declaredField =
                                            EntityFields.find(
                                                    oneshotEntity.getClass(), splitArr[1]);
                                    declaredField.setAccessible(true);
                                    classId = (Integer) declaredField.get(oneshotEntity);
                                    break;
                                } catch (NoSuchFieldException | IllegalAccessException e) {
                                    log.warn("审计栏目字段解析失败");
                                }
                            } else if (obj instanceof ContentEntity) {
                                ContentEntity contentEntity = (ContentEntity) obj;
                                try {
                                    Field declaredField =
                                            EntityFields.find(
                                                    contentEntity.getClass(), splitArr[1]);
                                    declaredField.setAccessible(true);
                                    classId = (Integer) declaredField.get(contentEntity);
                                    break;
                                } catch (NoSuchFieldException | IllegalAccessException e) {
                                    log.warn("审计栏目字段解析失败");
                                }
                            }
                        }
                    }
                }
                if (checkAuthority(request, response, methodSignature, classId)) {
                    return true;
                }
            }
            builderLogEntity(
                    joinPoint,
                    request,
                    methodSignature,
                    className,
                    methodName,
                    classId,
                    actionName);
        }
        return false;
    }

    private boolean checkAuthority(
            HttpServletRequest request,
            HttpServletResponse response,
            MethodSignature methodSignature,
            int classId) {
        if (methodSignature.getReturnType().getSimpleName().equalsIgnoreCase("ResultVO")) {
            // 栏目权限判断
            ResultVO resultVO =
                    AuthorityUtils.checkAuthorityForResultVO(classId, request, response);
            return resultVO != null;
        } else {
            // 栏目权限判断
            String responseResult =
                    AuthorityUtils.checkAuthorityForView(classId, request, response);
            return responseResult != null;
        }
    }

    /**
     * 构建日志对象
     *
     * @param joinPoint
     * @param request
     * @param methodSignature
     * @param className
     * @param methodName
     * @param classId
     * @param actionName
     */
    private void builderLogEntity(
            JoinPoint joinPoint,
            HttpServletRequest request,
            MethodSignature methodSignature,
            String className,
            String methodName,
            int classId,
            String actionName) {
        OneshotEntity modelById = oneShotService.getModelById(classId);
        if (modelById != null) {
            actionName =
                    actionName
                            .replace("单篇栏目 - ", "单篇栏目(" + modelById.getClassName() + ") - ")
                            .replace("列表栏目 - ", "列表栏目(" + modelById.getClassName() + ") - ");
        }
        UserEntity loginUser = TaleUtils.getLoginUser(request);
        currentLog().setAction(actionName);
        currentLog().setRequestData(null);
        assert loginUser != null;
        currentLog().setUserId(loginUser.getId());
        currentLog().setIp(RequestOriginUtils.clientIp(request));
        currentLog().setCreateTime(LocalDateTime.now());
        currentLog().setUrl(request.getRequestURI());
        currentLog().setHttpMethod(request.getMethod());
        currentLog().setClassMethod(className + "." + methodName);
        currentLog().setUserName(loginUser.getUsername());
    }

    /**
     * 后置通知
     *
     * @param ret
     */
    @AfterReturning(returning = "ret", pointcut = "webLog()")
    public void doAfterReturning(Object ret) {
        finishAudit(false, returnedStatus(ret));
    }

    private String returnedStatus(Object returned) {
        return returned instanceof org.springframework.http.ResponseEntity<?> response
                ? "HTTP_" + response.getStatusCode().value()
                : "RETURNED";
    }

    private void finishAudit(boolean failed, String returnedStatus) {
        AuditFrame frame = currentAudit();
        if (frame == null) return;
        try {
            LogEntity entry = frame.entry();
            long elapsed = System.currentTimeMillis() - frame.startedAt();
            entry.setSpendTime(elapsed);
            if (failed) entry.setResponseData("FAILED");
            else if (!"FAILED".equals(entry.getResponseData()))
                entry.setResponseData(returnedStatus);
            log.info(
                    "审计 URL={} METHOD={} USER={} RESULT={} TIME={}ms",
                    entry.getUrl(),
                    entry.getHttpMethod(),
                    entry.getUserId(),
                    entry.getResponseData(),
                    elapsed);
            if (StringUtils.isNotBlank(entry.getAction())) logService.addLog(entry);
        } finally {
            Deque<AuditFrame> frames = auditFrames.get();
            frames.pop();
            if (frames.isEmpty()) auditFrames.remove();
        }
    }

    /** 异常通知 */
    @AfterThrowing(value = "webLog()", throwing = "ex")
    public void throwsException(Throwable ex) {
        log.warn("请求处理异常");
        finishAudit(true, "FAILED");
    }
}
