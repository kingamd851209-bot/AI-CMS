package com.xinhongru.utils;

import jakarta.servlet.http.HttpServletResponse;

import lombok.extern.slf4j.Slf4j;

import org.springframework.web.util.DisconnectedClientHelper;

import java.io.IOException;

/**
 * @Description 进度条处理 @Copyright (c) 1998-2022 北京新鸿儒世纪网络技术有限公司 All Rights Reserved. @Url
 * https://www.xinhongru.com @ClassName HtmlProgressBar @Author yutiebing
 * <812022774@qq.com> @Version V1.0.0 @Since 1.0 @Date 2021/12/26 11:00
 */
@Slf4j
public class HtmlProgressBar {
    /**
     * 进度条的初始化
     *
     * @param response
     */
    public static void Start(HttpServletResponse response) {
        Start("正在加载...", response);
    }

    /**
     * 进度条的初始化
     *
     * @param msg 最开始显示的信息
     * @param response
     */
    public static void Start(String msg, HttpServletResponse response) {
        if (response == null) {
            return;
        }
        try {
            StringBuilder stringBuilder = new StringBuilder();
            stringBuilder.append(
                    "<html xmlns=\"http://www.w3.org/1999/xhtml\">\r\n"
                            + "<head>\r\n"
                            + "<title></title>\r\n"
                            + "<style>body {text-align:center;margin-top: 50px;}#ProgressBarSide"
                            + " {height:25px;border:1px"
                            + " #2F2F2F;width:95%;background:#EEFAFF;}</style><script"
                            + " language=\"javascript\">\r\n");
            stringBuilder.append("function setProgressBar(msg, pos)\r\n");
            stringBuilder.append("{\r\n");
            stringBuilder.append(
                    "document.getElementById('ProgressBar').style.width = pos + \"%\";\r\n");
            stringBuilder.append("WriteText('Msg1',msg + \" 已完成\" + pos + \"%\");\r\n");
            stringBuilder.append("}\r\n");
            stringBuilder.append(
                    "function setCompleted(msg)\r\n"
                            + "{\r\n"
                            + "if(msg==\"\")\r\n"
                            + "WriteText(\"Msg1\",\"完成。\");\r\n");
            stringBuilder.append("else\r\nWriteText(\"Msg1\",msg);\r\n}\r\n");
            stringBuilder.append("function WriteText(id, str)\r\n");
            stringBuilder.append("{\r\n");
            stringBuilder.append(
                    "var strTag = '<span style=\"font-family:Verdana, Arial,"
                        + " Helvetica;font-size=11.5px;color:#DD5800\">' + str + '</span>';\r\n");
            stringBuilder.append("document.getElementById(id).innerHTML = strTag;\r\n");
            stringBuilder.append("}\r\n");
            stringBuilder.append("</script>\r\n</head>\r\n<body>\r\n");
            stringBuilder.append(
                    "<div id=\"Msg1\"><span style=\"font-family:Verdana, Arial,"
                            + " Helvetica;font-size=11.5px;color:#DD5800\">"
                            + msg
                            + "</span></div>\r\n");
            stringBuilder.append(
                    "<div id=\"ProgressBarSide\" align=\"left\" style=\"margin:0"
                            + " auto;color:Silver;border-width:1px;border-style:Solid;\">\r\n");
            stringBuilder.append(
                    "<div id=\"ProgressBar\" style=\"background-color:#008BCE; height:25px;"
                            + " width:0%;color:#fff;\"></div>\r\n");
            stringBuilder.append("</div>\r\n</body>\r\n</html>\r\n");
            response.setContentType("text/html;charset=utf-8");
            response.getWriter().write(stringBuilder.toString());
            response.flushBuffer();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 滚动进度条
     *
     * @param Msg 消息提示
     * @param Pos 百分比
     * @param response
     * @throws IOException
     */
    public static Boolean Roll(String Msg, double Pos, HttpServletResponse response) {
        if (response == null) {
            return true;
        }
        try {
            String jsBlock =
                    "<script language=\"javascript\">setProgressBar('"
                            + Msg
                            + "',"
                            + Pos
                            + ");</script>";
            response.setContentType("text/html;charset=utf-8");
            response.getWriter().write(jsBlock);
            response.flushBuffer();
            return true;
        } catch (IOException e) {
            if (DisconnectedClientHelper.isClientDisconnectedException(e)) {
                response.setStatus(200);
                response.setContentType("text/html;charset=utf-8");
                log.info("客户端手动终止请求:{}", e.getMessage());
            } else {
                log.error(e.getMessage());
            }
            return false;
        }
    }
}
