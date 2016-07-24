package net.avicus.quest.model;

import lombok.Getter;
import net.avicus.quest.annotation.Column;
import net.avicus.quest.annotation.Id;
import net.avicus.quest.database.Database;
import net.avicus.quest.database.DatabaseException;
import net.avicus.quest.query.Row;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.*;

public class Table<M extends Model> {
    @Getter private final Database database;
    @Getter private final String name;
    @Getter private final Class<M> model;

    public Table(Database database, String name, Class<M> model) {
        this.database = database;
        this.name = name;
        this.model = model;
    }

    public ModelSelect<M> select() {
        return new ModelSelect<>(this);
    }

    public ModelInsert<M> insert(M instance) {
        return new ModelInsert<>(this, instance);
    }

    public ModelUpdate<M> update() {
        return new ModelUpdate<>(this);
    }

    public ModelDelete<M> delete() {
        return new ModelDelete<>(this);
    }

    public void create() {
        Map<String, String> columns = new LinkedHashMap<>();

        for (Map.Entry<Field, Column> entry : this.getColumnFields().entrySet()) {
            Field field = entry.getKey();
            Column column = entry.getValue();
            String name = this.getColumnName(field, column);
            String type = this.getColumnType(field, column);
            columns.put(name, type);
        }

        this.database.createTable(this.name, columns);
    }

    Row fromInstance(M instance) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<Field, Column> entry : this.getColumnFields().entrySet()) {
            Field field = entry.getKey();

            if (isId(field))
                continue;

            field.setAccessible(true);

            String name = this.getColumnName(field, entry.getValue());
            Object value;
            try {
                value = field.get(instance);
            } catch (IllegalAccessException e) {
                throw new DatabaseException("An unknown error occurred.", e);
            }

            values.put(name, value);
        }
        return new Row(values);
    }

    M newInstance(Row row) throws DatabaseException {
        M instance;

        try {
            instance = this.model.newInstance();
        } catch (Exception e) {
            throw new DatabaseException(String.format("Couldn't instantiate model via \"new %s()\".", this.model.getSimpleName()), e);
        }

        for (Map.Entry<Field, Column> entry : this.getColumnFields().entrySet()) {
            Field field = entry.getKey();
            field.setAccessible(true);

            String name = this.getColumnName(field, entry.getValue());
            Object value = row.get(name);

            if (field.getType() == boolean.class || field.getType() == Boolean.class)
                value = value != null && (value.equals(1) || value.equals("true"));

            if (value == null)
                continue;
            try {
                field.set(instance, value);
            } catch (IllegalAccessException e) {
                throw new DatabaseException("An unknown error occurred.", e);
            }
        }
        return instance;
    }

    M applyAutoGenerated(M instance, Optional<Integer> execute) {
        if (execute.isPresent()) {
            Optional<Field> field = getId();
            if (field.isPresent()) {
                field.get().setAccessible(true);
                try {
                    field.get().set(instance, execute.get());
                } catch (IllegalAccessException e) {
                    throw new DatabaseException("An unknown error occurred.", e);
                }
            }
        }
        return instance;
    }

    List<String> getColumns() {
        List<String> names = new ArrayList<>();
        for (Map.Entry<Field, Column> entry : this.getColumnFields().entrySet())
            names.add(this.getColumnName(entry.getKey(), entry.getValue()));
        return names;
    }

    private Map<Field, Column> getColumnFields() {
        Map<Field, Column> columns = new LinkedHashMap<>();
        for (Field field : this.model.getDeclaredFields()) {
            Optional<Column> column = getColumn(field);
            if (column.isPresent())
                columns.put(field, column.get());
        }
        return columns;
    }

    private Optional<Column> getColumn(Field field) {
        return Optional.ofNullable((Column) getAnnotations(field).get(Column.class));
    }

    private String getColumnName(Field field, Column column) {
        if (column.name().length() == 0)
            return field.getName();
        return column.name();
    }

    private String getColumnType(Field field, Column column) {
        if (column.type().length() != 0)
            return column.type();

        String type;
        if (field.getType() == String.class) {
            if (column.text())
                type = "TEXT";
            else
                type = "VARCHAR(" + (column.length() < 0 ? 255 : column.length()) + ")";
        }
        else if (field.getType() == int.class)
            type = "INT";
        else if (field.getType() == double.class)
            type = "DOUBLE";
        else if (field.getType() == float.class)
            type = "FLOAT";
        else if (field.getType() == boolean.class)
            type = "TINYINT";
        else if (field.getType() == Date.class)
            type = "DATETIME";
        else
            throw new DatabaseException(field.getType().getSimpleName() + " is not supported.");

        return type + " " + getColumnModifiers(field, column);
    }

    private String getColumnModifiers(Field field, Column column) {
        List<String> sql = new ArrayList<>();
        if (isId(field))
            sql.add("AUTO_INCREMENT");
        if (!column.nullable() || isId(field))
            sql.add("NOT NULL");
        if (column.primaryKey() || isId(field))
            sql.add("PRIMARY KEY");
        if (column.unique())
            sql.add("UNIQUE");
        if (column.def().length() > 0) {
            try {
                int value = Integer.parseInt(column.def());
                sql.add("DEFAULT " + value);
            } catch (Exception e) {
                sql.add("DEFAULT '" + column.def() + "'");
            }
        }

        return String.join(" ", sql);
    }

    private Optional<Field> getId() {
        for (Field field : this.getColumnFields().keySet())
            if (isId(field))
                return Optional.of(field);
        return Optional.empty();
    }

    private boolean isId(Field field) {
        return getAnnotationTypes(field).contains(Id.class);
    }

    private Map<Class<? extends Annotation>,Annotation> getAnnotations(Field field) {
        Map<Class<? extends Annotation>, Annotation> types = new HashMap<>();
        for (Annotation annotation : field.getAnnotations())
            types.put(annotation.annotationType(), annotation);
        return types;
    }

    private List<Class<? extends Annotation>> getAnnotationTypes(Field field) {
        return new ArrayList<>(getAnnotations(field).keySet());
    }
}
