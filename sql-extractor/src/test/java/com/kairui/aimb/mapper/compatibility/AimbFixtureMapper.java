package com.kairui.aimb.mapper.compatibility;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;

@Mapper
public interface AimbFixtureMapper extends BaseMapper<AimbFixtureMapper.UserDoctor> {
    long dynamicQuery(@Param("id") Long id);

    @Select("SELECT 'FROM log_history' AS note, id FROM user_doctor /* JOIN log_request */ WHERE id=#{id}")
    long literalQuery(@Param("id") Long id);

    @SelectProvider(type = Queries.class, method = "query")
    long providerQuery(@Param("id") Long id);

    class Queries {
        public static String query() { return "SELECT id FROM user_doctor WHERE id=#{id}"; }
    }

    class UserDoctor {
        @TableId public Long id;
        public String name;
    }
}
