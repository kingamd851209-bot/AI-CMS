package com.xinhongru.persistence;

import static org.assertj.core.api.Assertions.*;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.xinhongru.common.entity.*;
import com.xinhongru.config.MyBatisPlusConfig;
import com.xinhongru.dto.LogDto;
import com.xinhongru.entity.LogEntity;
import com.xinhongru.entity.OptionsEntity;
import com.xinhongru.mapper.LogMapper;
import com.xinhongru.mapper.OptionMapper;

import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

class PersistenceContractTest {
    SqlSessionFactory factory;

    @TableName("int_record")
    public static class IntRecord extends IntegerEntity<IntRecord> {}

    @TableName("long_record")
    public static class LongRecord extends LongEntity<LongRecord> {}

    interface IntMapper extends CmsMapper<IntRecord> {}

    interface LongMapper extends CmsMapper<LongRecord> {}

    @BeforeEach
    void setup() throws Exception {
        var ds =
                new DriverManagerDataSource(
                        "jdbc:h2:mem:"
                                + UUID.randomUUID()
                                + ";MODE=MySQL;NON_KEYWORDS=VALUE;DB_CLOSE_DELAY=-1",
                        "sa",
                        "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("create table int_record(id int auto_increment primary key)");
        jdbc.execute("create table long_record(id bigint auto_increment primary key)");
        jdbc.execute(
                "create table t_logs(id int primary key, action varchar(100), request_data"
                        + " varchar(100), user_id int, ip varchar(100), create_time timestamp,"
                        + " user_name varchar(100), spend_time int)");
        for (int i = 1; i <= 7; i++)
            jdbc.update(
                    "insert into t_logs(id,action,user_id) values(?,?,?)",
                    i,
                    i <= 5 ? "edit" : "login",
                    i == 7 ? 13 : 20);
        jdbc.execute(
                "create table t_options(id int primary key, name varchar(100), `value`"
                        + " varchar(100), description varchar(100))");
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(IntMapper.class);
        config.addMapper(LongMapper.class);
        var builder = new MybatisSqlSessionFactoryBean();
        builder.setDataSource(ds);
        builder.setConfiguration(config);
        builder.setMapperLocations(
                new ClassPathResource("mapper/LogMapper.xml"),
                new ClassPathResource("mapper/OptionMapper.xml"));
        builder.setPlugins(new MyBatisPlusConfig().mybatisPlusInterceptor());
        factory = builder.getObject();
    }

    @Test
    void optionsCanBeDeletedByNameWithoutAffectingOtherOptions() {
        try (var session = factory.openSession(true)) {
            var mapper = session.getMapper(OptionMapper.class);
            mapper.addOption(new OptionsEntity().setId(1).setName("first").setValue("one"));
            mapper.addOption(new OptionsEntity().setId(2).setName("second").setValue("two"));
            assertThat(mapper.deleteOptionByName("first")).isEqualTo(1);
            assertThat(mapper.getOptionByName("first")).isNull();
            assertThat(mapper.getOptionByName("second").getValue()).isEqualTo("two");
        }
    }

    @Test
    void inheritedIntegerAndLongIdentifiersUseGeneratedKeys() {
        try (var session = factory.openSession(true)) {
            var integer = new IntRecord();
            var longer = new LongRecord();
            session.getMapper(IntMapper.class).insert(integer);
            session.getMapper(LongMapper.class).insert(longer);
            assertThat(integer.getId()).isEqualTo(1);
            assertThat(longer.getId()).isEqualTo(1L);
            assertThat(session.getMapper(IntMapper.class).selectById(1).getId()).isEqualTo(1);
        }
    }

    @Test
    void explicitPaginationCountsFiltersAndDoesNotAffectFollowingQueries() {
        try (var session = factory.openSession()) {
            var mapper = session.getMapper(LogMapper.class);
            var query = new LogDto().setUserId(20).setKey("edit").setUserIds(List.of(20));
            var page = Pages.<LogEntity>query(2, 2, p -> mapper.getLogList(p, query));
            assertThat(page.total()).isEqualTo(5);
            assertThat(page.list()).extracting("id").containsExactly(3, 2);
            assertThat(mapper.getLogList(query)).hasSize(5);
            assertThat(mapper.getLogs(20)).hasSize(6);
            var empty = Pages.<LogEntity>query(99, 2, p -> mapper.getLogList(p, query));
            assertThat(empty.list()).isEmpty();
            assertThat(empty.total()).isEqualTo(5);
            var clamped = Pages.<LogEntity>query(-1, 0, p -> mapper.getLogs(p, 20));
            assertThat(clamped.pageNum()).isEqualTo(1);
            assertThat(clamped.pageSize()).isEqualTo(1);
        }
    }
}
