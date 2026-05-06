package com.yupi.springbootinit.utils;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.support.ExcelTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import cn.hutool.core.io.IoUtil;

/**
 * Excel 相关工具类
 */
@Slf4j
public class ExcelUtils {

    /**
     * 根据后缀将上传文件转为 CSV 文本：支持 .csv（按 UTF-8 读取）、.xlsx（EasyExcel 解析）
     *
     * @param multipartFile 上传文件
     * @return CSV 字符串，失败或空表返回空串
     */
    public static String multipartToCsvData(MultipartFile multipartFile) {
        if (multipartFile == null || multipartFile.isEmpty()) {
            return "";
        }
        String suffix = FileUtil.getSuffix(multipartFile.getOriginalFilename());
        if (StringUtils.isNotBlank(suffix) && "csv".equalsIgnoreCase(suffix)) {
            try {
                String text = IoUtil.read(multipartFile.getInputStream(), StandardCharsets.UTF_8);
                if (text.startsWith("\uFEFF")) {
                    text = text.substring(1);
                }
                return text.trim();
            } catch (IOException e) {
                log.error("CSV 读取失败", e);
                return "";
            }
        }
        return excelToCsv(multipartFile);
    }

    /**
     * excel 转 csv
     *
     * @param multipartFile
     * @return
     */
    public static String excelToCsv(MultipartFile multipartFile) {
        // 读取数据
        List<Map<Integer, String>> list = null;
        try {
            list = EasyExcel.read(multipartFile.getInputStream())
                    .excelType(ExcelTypeEnum.XLSX)
                    .sheet()
                    .headRowNumber(0)
                    .doReadSync();
        } catch (IOException e) {
            log.error("表格处理错误", e);
        }
        if (CollUtil.isEmpty(list)) {
            return "";
        }
        // 转换为 csv
        StringBuilder stringBuilder = new StringBuilder();
        // 读取表头
        LinkedHashMap<Integer, String> headerMap = (LinkedHashMap) list.get(0);
        List<String> headerList = headerMap.values().stream().filter(ObjectUtils::isNotEmpty).collect(Collectors.toList());
        stringBuilder.append(StringUtils.join(headerList, ",")).append("\n");
        // 读取数据
        for (int i = 1; i < list.size(); i++) {
            LinkedHashMap<Integer, String> dataMap = (LinkedHashMap) list.get(i);
            List<String> dataList = dataMap.values().stream().filter(ObjectUtils::isNotEmpty).collect(Collectors.toList());
            stringBuilder.append(StringUtils.join(dataList, ",")).append("\n");
        }
        return stringBuilder.toString();
    }

    public static void main(String[] args) {
        excelToCsv(null);
    }
}
