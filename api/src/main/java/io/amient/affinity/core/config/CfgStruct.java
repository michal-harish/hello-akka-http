package io.amient.affinity.core.config;

import com.typesafe.config.Config;

import java.net.URL;
import java.nio.file.Path;
import java.util.*;

public class CfgStruct<T extends CfgStruct> extends Cfg<T> implements CfgNested {

    private final List<Options> options;

    private List<Map.Entry<String, Cfg<?>>> properties = new LinkedList<>();

    private Config config;

    Set<String> extensions = new HashSet<String>() {{
        addAll(specializations());
    }};

    protected Set<String> specializations() {
        return Collections.emptySet();
    }

    public CfgStruct(Class<? extends CfgStruct<?>> inheritFrom, Options... options) {
        this.options = Arrays.asList(options);
        try {
            CfgStruct<?> inheritedCfg = inheritFrom.newInstance();
            inheritedCfg.properties.forEach(p -> extensions.add(p.getKey()));
            inheritedCfg.extensions.forEach(e -> extensions.add(e));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public CfgStruct(Options... options) {
        this.options = Arrays.asList(options);
    }

    public CfgStruct() {
        this(Options.STRICT);
    }

    @Override
    void setPath(String path) {
        super.setPath(path);
        properties.forEach(entry -> entry.getValue().setPath(path(entry.getKey())));
    }

    public final T apply(CfgStruct<?> conf) throws IllegalArgumentException {
        return apply(conf.config());
    }

    @Override
    public T apply(Config config) throws IllegalArgumentException {
        if (config == null) throw new IllegalArgumentException("null config passed (" + path() + ")");
        this.config = path().isEmpty() ? config : listPos > -1
                ? config.getConfigList(relPath).get(listPos) : config.getConfig(relPath);
        final StringBuilder errors = new StringBuilder();
        properties.forEach(entry -> {
            String propPath = entry.getKey();
            Cfg<?> cfg = entry.getValue();
            try {
                if (propPath == null || propPath.isEmpty()) {
                    cfg.apply(this.config);
                } else if (this.config.hasPath(propPath)) {
                    cfg.apply(this.config);
                }
                if (cfg.required && !cfg.isDefined()) {
                    throw new IllegalArgumentException(propPath + " is required" + (path().isEmpty() ? "" : " in " + path()));
                }
            } catch (IllegalArgumentException e) {
                errors.append(e.getMessage() + "\n");
            }
        });
        if (!options.contains(Options.IGNORE_UNKNOWN)) {
            this.config.entrySet().forEach(entry -> {
                boolean existingProperty = properties.stream().filter((p) ->
                        p.getKey().equals(entry.getKey())
                                || (p.getValue() instanceof CfgNested && entry.getKey().startsWith(p.getKey() + "."))
                ).count() > 0;
                boolean allowedViaExtensions = extensions.stream().filter( (s) ->
                        s.equals(entry.getKey()) || entry.getKey().startsWith(s + ".")
                ).count() > 0;
                if (!existingProperty && !allowedViaExtensions) {
                    errors.append(entry.getKey() + " is not a known property" + (path().isEmpty() ? "" : " of " + path()) + "\n");
                }
            });
        }
        String errorMessage = errors.toString();
        if (!errorMessage.isEmpty()) {
            throw new IllegalArgumentException(errorMessage);
        }
        return (T) setValue((T) this);

    }

    public Config config() {
        return config;
    }

    public CfgString string(String path, boolean required) {
        return add(path, new CfgString(), required, Optional.empty());
    }

    public CfgString string(String path, String defaultValue) {
        return add(path, new CfgString(), true, Optional.of(defaultValue));
    }

    public CfgLong longint(String path, boolean required) {
        return add(path, new CfgLong(), required, Optional.empty());
    }

    public CfgLong longint(String path, long defaultValue) {
        return add(path, new CfgLong(), true, Optional.of(defaultValue));
    }

    public CfgBool bool(String path, boolean required) {
        return add(path, new CfgBool(), required, Optional.empty());
    }

    public CfgBool bool(String path, boolean required, boolean defaultValue) {
        return add(path, new CfgBool(), required, Optional.of(defaultValue));
    }


    public CfgInt integer(String path, boolean required) {
        return add(path, new CfgInt(), required, Optional.empty());
    }

    public CfgInt integer(String path, Integer defaultValue) {
        return add(path, new CfgInt(), true, Optional.of(defaultValue));
    }

    public CfgUrl url(String path, boolean required) {
        return add(path, new CfgUrl(), required, Optional.empty());
    }

    public CfgUrl url(String path, URL defaultVal) {
        return add(path, new CfgUrl(), true, Optional.of(defaultVal));
    }

    public CfgPath filepath(String path, boolean required) {
        return add(path, new CfgPath(), required, Optional.empty());
    }

    public CfgPath filepath(String path, Path defaultVal) {
        return add(path, new CfgPath(), true, Optional.of(defaultVal));
    }

    public <X> CfgCls<X> cls(String path, Class<X> c, boolean required) {
        return add(path, new CfgCls<>(c), required, Optional.empty());
    }

    public <X> CfgCls<X> cls(String path, Class<X> c, Class<? extends X> defaultVal) {
        return add(path, new CfgCls<>(c), true, Optional.of(defaultVal));
    }

    public <X extends CfgStruct<X>> X struct(String path, X obj, boolean required) {
        return add(path, obj, required, Optional.empty());
    }

    public <X extends CfgStruct<X>> X ref(X obj, boolean required) {
        return add(null, obj, required, Optional.empty());
    }


    public <X extends Cfg<?>> CfgGroup<X> group(String path, Class<X> c, boolean required) {
        return add(path, new CfgGroup<>(c), required, Optional.empty());
    }

    public <X extends Cfg<?>> CfgGroup<X> group(String path, CfgGroup<X> obj, boolean required) {
        return add(path, obj, required, Optional.empty());
    }

    public <X, Y extends Cfg<X>> CfgList<X, Y> list(String path, Class<X> c, boolean required) {
        return add(path, new CfgList(c), required, Optional.empty());
    }


    private <Y, X extends Cfg<Y>> X add(String path, X cfg, boolean required, Optional<Y> defaultValue) {
        cfg.setRelPath(path);
        cfg.setPath(path);
        if (defaultValue.isPresent()) cfg.setDefaultValue(defaultValue.get());
        if (!required) cfg.setOptional();
        properties.add(new AbstractMap.SimpleEntry<>(path, cfg));
        return cfg;

    }

}