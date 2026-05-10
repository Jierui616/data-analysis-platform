package com.yupi.springbootinit.manager;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.GetObjectRequest;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.PutObjectResult;
import com.yupi.springbootinit.config.CosClientConfig;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import javax.annotation.Resource;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Cos 对象存储操作
 *
 */
@Component
public class CosManager {

    @Resource
    private CosClientConfig cosClientConfig;

    @Resource
    private COSClient cosClient;

    /**
     * 上传对象
     *
     * @param key 唯一键
     * @param localFilePath 本地文件路径
     * @return
     */
    public PutObjectResult putObject(String key, String localFilePath) {
        PutObjectRequest putObjectRequest = new PutObjectRequest(cosClientConfig.getBucket(), key,
                new File(localFilePath));
        return cosClient.putObject(putObjectRequest);
    }

    /**
     * 上传对象
     *
     * @param key 唯一键
     * @param file 文件
     * @return
     */
    public PutObjectResult putObject(String key, File file) {
        PutObjectRequest putObjectRequest = new PutObjectRequest(cosClientConfig.getBucket(), key,
                file);
        return cosClient.putObject(putObjectRequest);
    }

    /**
     * 上传 UTF-8 文本（图表 CSV 外置等）。
     */
    public PutObjectResult putObjectUtf8(String bucket, String key, byte[] utf8Bytes) {
        ObjectMetadata meta = new ObjectMetadata();
        meta.setContentLength(utf8Bytes.length);
        meta.setContentType("text/csv; charset=utf-8");
        PutObjectRequest req = new PutObjectRequest(bucket, key, new ByteArrayInputStream(utf8Bytes), meta);
        return cosClient.putObject(req);
    }

    /**
     * 下载对象为 UTF-8 字符串。
     */
    public String getObjectAsString(String bucket, String key) {
        GetObjectRequest getReq = new GetObjectRequest(bucket, key);
        COSObject cosObject = cosClient.getObject(getReq);
        try (InputStream in = cosObject.getObjectContent()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("COS 读取对象失败: " + key, e);
        }
    }
}
