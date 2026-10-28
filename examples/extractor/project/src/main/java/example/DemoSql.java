package example;

/** 源码提取示例，无需编译或连接数据库。 */
public class DemoSql {
    public String query() {
        return "SELECT id, name FROM demo WHERE id = 1";
    }
}
