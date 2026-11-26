package com.xinhongru.utils;

import com.xinhongru.constant.WebConst;

import lombok.extern.slf4j.Slf4j;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @Description 敏感词库初始化类 @ClassName SensitiveWordUtils @Author 永远的神 @Version V1.0.0 @Since 1.0 @Date
 * 2022/8/13 23:52
 */
@Slf4j
public class SensitiveWordUtils {
    /** 替换为字符 */
    private static String replaceChar = "*";

    /** 最小匹配规则 */
    private static int minMatchTYpe = 1;

    /** 最大匹配规则 */
    private static int maxMatchType = 2;

    /** 敏感词容器 */
    private static HashMap sensitiveWordMap;

    public SensitiveWordUtils() {
        super();
    }

    /**
     * 初始化词库
     *
     * @return
     */
    public static HashMap init() {
        log.info("初始化敏感词库");
        List<String> sensitiveWordList = null;
        try {
            sensitiveWordList = new ArrayList<>(readSensitiveWordFile());
        } catch (Exception e) {
            log.info("初始化敏感词库失败");
            e.printStackTrace();
        }
        if (null != sensitiveWordList) {
            addSensitiveWord(sensitiveWordList);
        }
        return sensitiveWordMap;
    }

    /**
     * 读取敏感词库，将敏感词放入HashSet中，构建一个DFA算法模型
     *
     * @param datas
     */
    public static void addSensitiveWord(List<String> datas) {
        sensitiveWordMap = new HashMap(datas.size());
        Iterator<String> iterator = datas.iterator();
        Map<String, Object> now;
        Map now2;
        while (iterator.hasNext()) {
            now2 = sensitiveWordMap;
            // 敏感词
            String word = iterator.next().trim();
            for (int i = 0; i < word.length(); i++) {
                char key_word = word.charAt(i);
                Object obj = now2.get(key_word);
                // 存在
                if (obj != null) {
                    now2 = (Map) obj;
                } else { // 不存在
                    now = new HashMap<>();
                    now.put("isEnd", "0");
                    now2.put(key_word, now);
                    now2 = now;
                }
                if (i == word.length() - 1) {
                    now2.put("isEnd", "1");
                }
            }
        }
    }

    /**
     * 获取内容中的敏感词
     *
     * @param text 内容
     * @param matchType 匹配规则 1=不最佳匹配，2=最佳匹配
     * @return
     */
    public static List<String> getSensitiveWord(String text, int matchType) {
        List<String> words = new ArrayList<>();
        Map now = sensitiveWordMap;
        // 初始化敏感词长度
        int count = 0;
        // 标志敏感词开始的下标
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char key = text.charAt(i);
            now = (Map) now.get(key);
            // 存在
            if (now != null) {
                count++;
                if (count == 1) {
                    start = i;
                }
                // 敏感词结束
                if ("1".equals(now.get("isEnd"))) {
                    // 重新获取敏感词库
                    now = sensitiveWordMap;
                    // 取出敏感词，添加到集合
                    words.add(text.substring(start, start + count));
                    // 初始化敏感词长度
                    count = 0;
                }
            } else {
                start = i;
                count = 0;
                // 不存在
                // 重新获取敏感词库
                now = sensitiveWordMap;
                // 不最佳匹配
                if (count == 1 && matchType == 1) {
                    count = 0;
                } else if (count == 1 && matchType == 2) {
                    // 最佳匹配
                    words.add(text.substring(start, start + count));
                    count = 0;
                }
            }
        }
        // 删除重复项
        return words.stream().distinct().collect(Collectors.toList());
    }

    /**
     * 从文件中读取敏感词库中的内容，将内容添加到set集合中
     *
     * @return
     * @throws Exception
     */
    private static Set<String> readSensitiveWordFile() throws Exception {
        log.info("从sensitiveWord.txt中读取敏感词...");
        Set<String> set;

        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource resource =
                resolver.getResource("classpath:" + WebConst.SEPARATOR + "sensitiveWord.txt");
        // 获得文件流，因为在jar文件中，不能直接通过文件资源路径拿到文件，但是可以在jar包中拿到文件流
        InputStreamReader read =
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8);
        try {
            set = new HashSet<>();
            BufferedReader bufferedReader = new BufferedReader(read);
            String txt = null;
            // 读取文件，将文件内容放入到set中
            while ((txt = bufferedReader.readLine()) != null) {
                set.add(txt);
            }
            log.info("从sensitiveWord.txt中读取敏感词完毕");
        } catch (IOException e) {
            set = new HashSet<>();
            log.error("从sensitiveWord.txt中读取敏感词出现异常:{}", e.getMessage());
            e.printStackTrace();
        } finally {
            // 关闭文件流
            read.close();
        }
        return set;
    }

    /**
     * 替换敏感字字符
     *
     * @param txt
     * @return
     */
    public static String replaceSensitiveWord(String txt) {
        if (sensitiveWordMap == null) {
            sensitiveWordMap = init();
        }
        String resultTxt = txt;
        // 获取所有的敏感词
        List<String> set = getSensitiveWord(txt, minMatchTYpe);

        Iterator<String> iterator = set.iterator();
        String word;
        String replaceString;
        while (iterator.hasNext()) {
            word = iterator.next();
            replaceString = SensitiveWordUtils.getReplaceChars(replaceChar, word.length());
            resultTxt = resultTxt.replaceAll(word, replaceString);
        }
        return resultTxt;
    }

    /**
     * 获取替换字符串
     *
     * @param replaceChar
     * @param length 敏感词替代符号最多多少个字符
     * @return
     */
    public static String getReplaceChars(String replaceChar, int length) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(replaceChar);
        if (length > 6) {
            length = 6;
        }
        for (int i = 1; i < length; i++) {
            stringBuilder.append(replaceChar);
        }
        return stringBuilder.toString();
    }
}
