package com.kairui.tools.sqlexport.parameter;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Collection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SampleValueFactoryTest {
    private final SampleValueFactory factory = new SampleValueFactory();

    @Test
    void fillsImmutableBuilderParameters() {
        ImmutableValue value = (ImmutableValue) new SampleValueFactory().value(ImmutableValue.class);
        assertEquals("sample", value.name);
        assertEquals(1, value.year);
    }

    static class ImmutableValue {
        final String name;
        final Integer year;
        private ImmutableValue(String name, Integer year) { this.name = name; this.year = year; }
        static Builder builder() { return new Builder(); }
        public static class Builder {
            String name;
            Integer year;
            public Builder name(String value) { name = value; return this; }
            public Builder year(Integer value) { year = value; return this; }
            public ImmutableValue build() { return new ImmutableValue(name, year); }
        }
    }

    @Test
    void resolvesNamedAndMultipleParameters() throws Exception {
        Method method = ExampleMapper.class.getMethod("find", String.class, Integer.class);
        Map<?, ?> parameters = (Map<?, ?>) factory.parameters(new Configuration(), ExampleMapper.class, method);
        assertEquals("sample", parameters.get("name"));
        assertEquals(1, parameters.get("count"));
        assertEquals("sample", parameters.get("param1"));
        assertEquals(1, parameters.get("param2"));
    }

    @Test
    void resolvesSingleCollectionAliases() throws Exception {
        Method method = ExampleMapper.class.getMethod("findIds", List.class);
        Map<?, ?> parameters = (Map<?, ?>) factory.parameters(new Configuration(), ExampleMapper.class, method);
        assertEquals(List.of(1L), parameters.get("collection"));
        assertEquals(List.of(1L), parameters.get("list"));
    }

    @Test
    void resolvesInheritedBaseMapperEntityAndId() throws Exception {
        Method insert = BaseMapper.class.getMethod("insert", Object.class);
        Object parameters = factory.parameters(new Configuration(), ExampleMapper.class, insert);
        ExampleEntity entity = assertInstanceOf(ExampleEntity.class, parameters);
        assertEquals("sample", entity.id);
        assertEquals(1, entity.count);
        Method select = BaseMapper.class.getMethod("selectById", java.io.Serializable.class);
        Object id = factory.parameters(new Configuration(), ExampleMapper.class, select);
        assertEquals("sample", id);
    }

    @Test
    void fillsInheritedSimpleFieldsAndPreservesComplexFields() {
        ExampleEntity value = (ExampleEntity) factory.value(ExampleEntity.class);
        assertEquals("sample", value.inherited);
        assertNull(value.child);
        assertEquals("fixed", value.fixed);
    }

    @Test
    void buildsGenericUpdateAndIdCollectionArguments() throws Exception {
        Method update = BaseMapper.class.getMethod("update", Object.class, Wrapper.class);
        Map<?, ?> parameters = (Map<?, ?>) factory.parameters(new Configuration(), ExampleMapper.class, update);
        assertInstanceOf(ExampleEntity.class, parameters.get("et"));
        Wrapper<?> wrapper = assertInstanceOf(Wrapper.class, parameters.get("ew"));
        assertNull(wrapper.getEntity());
        Method batch = BaseMapper.class.getMethod("selectBatchIds", Collection.class);
        Map<?, ?> ids = (Map<?, ?>) factory.parameters(new Configuration(), ExampleMapper.class, batch);
        assertEquals(List.of("sample"), ids.get("coll"));
    }

    @Test
    void queryWrapperDoesNotAttachSampleEntity() throws Exception {
        Method select = BaseMapper.class.getMethod("selectCount", Wrapper.class);
        Map<?, ?> parameters = (Map<?, ?>) factory.parameters(new Configuration(), ExampleMapper.class, select);
        Wrapper<?> wrapper = assertInstanceOf(Wrapper.class, parameters.get("ew"));
        assertNull(wrapper.getEntity());
        assertEquals("", wrapper.getSqlSegment());
    }

    @Test
    void reportsUnsupportedTypesInsteadOfStringifyingThem() {
        assertThrows(IllegalArgumentException.class, () -> factory.value(Runnable.class));
    }

    interface ExampleMapper extends BaseMapper<ExampleEntity> {
        void find(@Param("name") String name, @Param("count") Integer count);
        void findIds(List<Long> ids);
    }

    static class ParentEntity {
        String inherited;
    }

    static class ExampleEntity extends ParentEntity {
        String id;
        Integer count;
        ChildEntity child;
        final String fixed = "fixed";
    }

    static class ChildEntity {
        ExampleEntity parent;
    }

}
