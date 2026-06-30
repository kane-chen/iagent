package io.invest.iagent.utils;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.invest.iagent.service.filing.model.FilingResult;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class FileUtils {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    /**
     * 创建目录（不存在则创建）
     */
    public static Path createDir(String dirPath) {
        Path path = Paths.get(dirPath);
        if (!Files.exists(path)) {
            try {
                Files.createDirectories(path);
            } catch (IOException e) {
                throw new RuntimeException("创建目录失败: " + dirPath, e);
            }
        }
        return path;
    }

    /**
     * 检查文件是否已存在
     */
    public static boolean fileExists(String filePath) {
        return Files.exists(Paths.get(filePath));
    }

    /**
     * 写入Manifest文件
     */
    public static void writeManifest(Path dir, List<FilingResult> results) {
        File manifestFile = dir.resolve("_manifest.json").toFile();
        try {
            OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(manifestFile, results);
        } catch (IOException e) {
            throw new RuntimeException("写入Manifest文件失败", e);
        }
    }

    /**
     * 下载文件到指定路径
     */
    public static void downloadFile(byte[] content, Path filePath) throws IOException {
        Files.write(filePath, content);
    }

    public static <T> T parseContent(File file, TypeReference<T> clazz) throws IOException {
        if (!file.exists()) {
            throw new java.io.FileNotFoundException(file.getAbsolutePath());
        }
        String content = Files.readString(file.toPath()) ;
        return JSON.parseObject(content, clazz);
    }
}