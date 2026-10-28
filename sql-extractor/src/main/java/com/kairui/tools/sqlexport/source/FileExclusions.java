package com.kairui.tools.sqlexport.source;

import cn.hutool.core.util.StrUtil;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 按完整路径段匹配文件或目录，目录规则也排除其全部子目录。 */
public final class FileExclusions {
    private final List<Path> rules = new ArrayList<>();

    /** 将非空规则规范化为路径；规则按路径段匹配，不解析通配符。 */
    public FileExclusions(String... paths) {
        for (String value : paths) {
            if (StrUtil.isBlank(value)) continue;
            Path rule = Path.of(value.trim()).normalize();
            if (rule.toString().isEmpty()) throw new IllegalArgumentException("排除路径不能是当前目录，请指定文件或目录");
            rules.add(rule);
        }
    }

    /**
     * 判断文件或目录是否命中排除规则。
     * 绝对规则匹配路径前缀，相对规则匹配文件或父目录的完整路径段后缀，不依赖工作目录。
     */
    public boolean excludes(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        for (Path rule : rules) {
            if (rule.isAbsolute()) {
                if (absolute.startsWith(rule)) return true;
            } else {
                // 相对路径匹配文件或任一父目录的路径段后缀，不依赖启动工作目录。
                for (Path current = absolute; current != null; current = current.getParent()) {
                    if (current.endsWith(rule)) return true;
                }
            }
        }
        return false;
    }
}
