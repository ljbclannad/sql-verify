package com.kairui.aimb.mapper.compatibility;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface LogHistoryMapper {
    @Select("SELECT id FROM ${tableName}")
    long dynamicLog();
}
