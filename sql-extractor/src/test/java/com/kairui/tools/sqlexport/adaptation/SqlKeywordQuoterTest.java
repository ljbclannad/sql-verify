package com.kairui.tools.sqlexport.adaptation;

import com.kairui.tools.sqlexport.model.ExtractedSql;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlKeywordQuoterTest {
    @Test
    void quotesOnlyUnquotedReservedIdentifiersAndPreservesLayout() {
        String sql = "SELECT KEY, t.rank, `group`, name FROM order t\r\n\tWHERE key = ? ORDER BY key";
        String expected = "SELECT `KEY`, t.`rank`, `group`, name FROM `order` t\r\n\tWHERE `key` = ? ORDER BY `key`";
        assertEquals(expected, SqlKeywordQuoter.quote(sql));
        assertEquals(expected, SqlKeywordQuoter.quote(expected));
    }

    @Test
    void preservesLiteralsCommentsFunctionsAndSqlGrammar() {
        String sql = "SELECT 'key rank order', \"key\", rank() OVER (ORDER BY key), "
                + "IF(key IS NULL, 'key', key), position('x' IN name) FROM t "
                + "/* key rank */ WHERE key = ? -- key\nORDER BY key DESC";
        String expected = "SELECT 'key rank order', \"key\", rank() OVER (ORDER BY `key`), "
                + "IF(`key` IS NULL, 'key', `key`), position('x' IN name) FROM t "
                + "/* key rank */ WHERE `key` = ? -- key\nORDER BY `key` DESC";
        assertEquals(expected, SqlKeywordQuoter.quote(sql));
    }

    @Test
    void quotesDmlColumnsAndTableNamesWithoutChangingParameters() {
        assertEquals("INSERT INTO `order` (`key`,`rank`) VALUES (#{key},#{rank,jdbcType=INTEGER})",
                SqlKeywordQuoter.quote("INSERT INTO order (key,rank) VALUES (#{key},#{rank,jdbcType=INTEGER})"));
        assertEquals("UPDATE t SET `key` = ?, `rank` = ? WHERE `key` = ?",
                SqlKeywordQuoter.quote("UPDATE t SET key = ?, rank = ? WHERE key = ?"));
        assertEquals("DELETE FROM t WHERE `key` IN (?,?)",
                SqlKeywordQuoter.quote("DELETE FROM t WHERE key IN (?,?)"));
        assertEquals("SELECT t.`key` FROM (SELECT `key` FROM t WHERE `rank` = ?) t",
                SqlKeywordQuoter.quote("SELECT t.key FROM (SELECT key FROM t WHERE rank = ?) t"));
    }

    @Test
    void preservesUnsupportedSqlAndExistingQuoting() {
        assertEquals("not a SQL key", SqlKeywordQuoter.quote("not a SQL key"));
        assertEquals("SELECT `key`, `rank` FROM `order`", SqlKeywordQuoter.quote("SELECT `key`, `rank` FROM `order`"));
    }

    @Test
    void quotesDynamicUpdateAndChooseFragmentsWithoutTouchingAttributes() {
        var sql = new ExtractedSql("sample.Mapper.update", "XML", "fixture", "", """
                <update id="update">
                  UPDATE keyword_fixture
                  <set>
                    <if test="key != null">key = #{key},</if>
                    <choose>
                      <when test="rank != null">rank = #{rank},</when>
                      <otherwise>rank = 0,</otherwise>
                    </choose>
                  </set>
                  WHERE key = #{key}
                </update>
                """);
        SqlKeywordQuoter.quote(sql);
        assertTrue(sql.template.contains("`key` = #{key}"), sql.template);
        assertTrue(sql.template.contains("`rank` = #{rank}"), sql.template);
        assertTrue(sql.template.contains("`rank` = 0"), sql.template);
        assertTrue(sql.template.contains("test=\"key != null\""));
        assertTrue(sql.template.contains("test=\"rank != null\""));
    }

    @Test
    void quotesForeachAndCdataWithoutChangingCollectionOrItemNames() {
        var sql = new ExtractedSql("sample.Mapper.find", "XML", "fixture", "", """
                <select id="find">
                  SELECT key FROM keyword_fixture
                  <where>
                    <![CDATA[key < #{key}]]>
                    AND rank IN <foreach collection="ranks" item="rank" open="(" close=")" separator=",">#{rank}</foreach>
                  </where>
                </select>
                """);
        SqlKeywordQuoter.quote(sql);
        assertTrue(sql.template.contains("SELECT `key` FROM"), sql.template);
        assertTrue(sql.template.contains("`key` &lt; #{key}") || sql.template.contains("`key` < #{key}"), sql.template);
        assertTrue(sql.template.contains("AND `rank` IN"), sql.template);
        assertTrue(sql.template.contains("item=\"rank\""));
        assertTrue(sql.template.contains("#{rank}"));
    }
}
