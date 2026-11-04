package dev.railrestock.integration;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Java-only adapter for the Litematica material list, reached through reflection
 * so no Litematica dependency is compiled in. All calls must happen on the
 * Minecraft client thread.
 *
 * <p>Only version-independent public getters are used. Serialization APIs such as
 * {@code toNbt}/{@code toJson} are deliberately avoided: they are persistence
 * concerns and are not guaranteed to be public in every build. This adapter never
 * installs a completion listener, clears ignored entries, changes material-list
 * settings or replaces the HUD material list.</p>
 */
public final class LitematicaBridge {
    public record Context(Object placement, Object materialList, String name) { }

    /** The original stack object is retained; the caller filters empty stacks. */
    public record Need(Object stack, long target, long available) {
        public long deficit() {
            return target > available ? target - available : 0L;
        }
    }

    public static final class BridgeException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public BridgeException(String message) {
            super(message);
        }

        public BridgeException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final String PREFIX = "fi.dy.masa.litematica.";
    private final ClassLoader loader;
    private volatile Api cached;
    private volatile BridgeException failure;

    public LitematicaBridge() {
        this(LitematicaBridge.class.getClassLoader());
    }

    public LitematicaBridge(ClassLoader loader) {
        if (loader == null) throw new BridgeException("Litematica 集成初始化失败：类加载器为空");
        this.loader = loader;
    }

    /** Null when no placement is selected; never creates a list for an unselected placement. */
    public Context resolveContext() {
        return guarded("读取选中的投影", () -> {
            Api api = api();
            Object placement = api.selectedPlacement();
            if (placement == null) return null;
            Object list = api.materialListOf(placement);
            String name = (String) invoke(api.getName, placement);
            return new Context(placement, list, name);
        });
    }

    public boolean isStillSelected(Context context) {
        if (context == null) return false;
        return guarded("检查投影选择是否变化", () -> {
            Api api = api();
            api.check(context);
            Object selected = api.selectedPlacement();
            return selected == context.placement()
                    && api.materialListOf(selected) == context.materialList();
        });
    }

    /** Reads enablement independently of any geometry or serialization API. */
    public boolean isPlacementEnabled(Context context) {
        return guarded("检查投影是否启用", () -> {
            Api api = api();
            api.check(context);
            return (Boolean) required(api.isEnabled, context.placement(), Boolean.class);
        });
    }

    public boolean isRecountPending() {
        return guarded("检查材料统计任务", () -> {
            Api api = api();
            return api.hasCountTask(api.clientScheduler());
        });
    }

    /** Schedules a count only when Litematica has no count task of its own running. */
    public void requestRecount(Context context) {
        guarded("请求重新统计材料", () -> {
            Api api = api();
            api.check(context);
            Object scheduler = api.clientScheduler();
            synchronized (scheduler) {
                if (!api.hasCountTask(scheduler)) invoke(api.reCreateMaterialList, context.materialList());
            }
            return null;
        });
    }

    /**
     * Updates availability, then reports the complete list minus the user's ignored
     * entries. The pre-filtered/filtered UI caches are not used because "hide
     * available" can permanently drop entries from them.
     */
    public List<Need> needs(Context context, Object player) {
        return guarded("读取材料需求", () -> {
            Api api = api();
            api.check(context);
            List<?> materials = api.materials(context.materialList());
            api.updateAvailable(materials, player);
            Set<?> ignored = api.ignored(context.materialList());
            long multiplier = positive(api.getMultiplier, context.materialList());
            List<Need> result = new ArrayList<>(materials.size());
            for (Object entry : materials) {
                if (entry == null || !api.entryType.isInstance(entry)) {
                    throw unsupported("getMaterialsAll 返回了空条目或非 MaterialListEntry 条目");
                }
                if (ignored.contains(entry)) continue;
                long count = nonNegative(multiplier == 1L ? api.getCountMissing : api.getCountTotal, entry);
                long target = multiplier == 1L ? count : saturatedMultiply(multiplier, count);
                long available = nonNegative(api.getCountAvailable, entry);
                result.add(new Need(invoke(api.getStack, entry), target, available));
            }
            return List.copyOf(result);
        });
    }

    /** Recomputes availability and rebuilds the views while preserving ignored entries. */
    public void refresh(Context context, Object player) {
        guarded("刷新材料清单", () -> {
            Api api = api();
            api.check(context);
            api.updateAvailable(api.materials(context.materialList()), player);
            invoke(api.refreshPreFilteredList, context.materialList());
            invoke(api.recreateFilteredList, context.materialList());
            return null;
        });
    }

    /**
     * In-process scope snapshot from placement/subregion getters only: origin,
     * rotation, mirror, enablement, every subregion's name/position/rotation/
     * mirror/enablement, the material list type and multiplier. Subregions are
     * sorted so collection order changes are not mistaken for a moved placement.
     * Render-layer bounds are read only for a RENDER_LAYERS list. Not a persistent
     * ID: schematic block edits and world progress are not detected here.
     */
    public String scopeFingerprint(Context context) {
        return guarded("读取投影计数范围", () -> {
            Api api = api();
            api.check(context);
            Geo geo = api.geo();
            StringBuilder snapshot = new StringBuilder("scope-v2;");
            part(snapshot, geo.positionText(required(geo.getOrigin, context.placement(), Object.class)));
            part(snapshot, ((Enum<?>) required(geo.getRotation, context.placement(), Enum.class)).name());
            part(snapshot, ((Enum<?>) required(geo.getMirror, context.placement(), Enum.class)).name());
            part(snapshot, required(api.isEnabled, context.placement(), Boolean.class));

            Object raw = required(geo.getSubRegions, context.placement(), Object.class);
            Iterable<?> regions = raw instanceof Map<?, ?> map ? map.values()
                    : raw instanceof Iterable<?> iterable ? iterable : null;
            if (regions == null) throw unsupported("getAllSubRegionsPlacements 返回值不是可遍历集合");

            List<String> states = new ArrayList<>();
            for (Object region : regions) {
                if (region == null) throw unsupported("子区域集合包含空值");
                RegionApi regionApi = geo.regions.computeIfAbsent(region.getClass(), RegionApi::new);
                StringBuilder state = new StringBuilder();
                part(state, required(regionApi.getName, region, String.class));
                part(state, geo.positionText(required(regionApi.getPos, region, Object.class)));
                part(state, ((Enum<?>) required(regionApi.getRotation, region, Enum.class)).name());
                part(state, ((Enum<?>) required(regionApi.getMirror, region, Enum.class)).name());
                part(state, required(regionApi.isEnabled, region, Boolean.class));
                states.add(state.toString());
            }
            Collections.sort(states);
            part(snapshot, states.size());
            for (String state : states) part(snapshot, state);

            Enum<?> listType = (Enum<?>) required(geo.getMaterialListType, context.materialList(), Enum.class);
            part(snapshot, listType.name());
            part(snapshot, positive(api.getMultiplier, context.materialList()));
            if (listType.name().equals("RENDER_LAYERS")) {
                Layers layers = geo.layers(api);
                Object range = required(layers.getRenderLayerRange, null, layers.rangeType);
                part(snapshot, ((Enum<?>) required(layers.getLayerMode, range, Enum.class)).name());
                part(snapshot, ((Enum<?>) required(layers.getAxis, range, Enum.class)).name());
                part(snapshot, integer(layers.getLayerMin, range));
                part(snapshot, integer(layers.getLayerMax, range));
            }
            return snapshot.toString();
        });
    }

    private static void part(StringBuilder builder, Object value) {
        String text = String.valueOf(value);
        builder.append(text.length()).append(':').append(text).append(';');
    }

    public static String describeFailure(Throwable failure) {
        if (failure == null) return "未知错误";
        StringBuilder result = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable next = failure; next != null && seen.add(next); next = next.getCause()) {
            if (!result.isEmpty()) result.append("；原因：");
            result.append(next.getClass().getSimpleName()).append("：").append(next.getMessage());
        }
        return result.toString();
    }

    private Api api() {
        Api result = cached;
        if (result != null) return result;
        synchronized (this) {
            if (cached != null) return cached;
            if (failure != null) throw failure;
            try {
                result = new Api(loader);
                cached = result;
                return result;
            } catch (BridgeException e) {
                failure = e;
                throw e;
            } catch (RuntimeException | LinkageError e) {
                failure = new BridgeException("Litematica API 初始化失败：" + describeFailure(e), e);
                throw failure;
            }
        }
    }

    private static <T> T guarded(String operation, Supplier<T> action) {
        try {
            return action.get();
        } catch (BridgeException e) {
            throw e;
        } catch (RuntimeException | LinkageError e) {
            throw new BridgeException("Litematica 集成失败（" + operation + "）：" + describeFailure(e), e);
        }
    }

    private static final class Api {
        final Class<?> dataManagerType;
        final Class<?> placementType;
        final Class<?> materialListType;
        final Class<?> entryType;
        final Class<?> utilsType;
        final Class<?> schedulerType;
        final Class<?> countTaskType;
        final Method getPlacementManager;
        final Method getSelectedPlacement;
        final Method getMaterialList;
        final Method getName;
        final Method isEnabled;
        final Method getMaterialsAll;
        final Method getMultiplier;
        final Method reCreateMaterialList;
        final Method refreshPreFilteredList;
        final Method recreateFilteredList;
        final Method getStack;
        final Method getCountMissing;
        final Method getCountTotal;
        final Method getCountAvailable;
        final Method getInstanceClient;
        final Method hasTask;
        final Field ignoredField;
        final Map<AvailableKey, Method> availableMethods = new ConcurrentHashMap<>();
        private Geo geo;
        private BridgeException geoFailure;

        Api(ClassLoader loader) {
            dataManagerType = load(loader, PREFIX + "data.DataManager");
            Class<?> managerType = load(loader, PREFIX + "schematic.placement.SchematicPlacementManager");
            placementType = load(loader, PREFIX + "schematic.placement.SchematicPlacement");
            materialListType = load(loader, PREFIX + "materials.MaterialListBase");
            entryType = load(loader, PREFIX + "materials.MaterialListEntry");
            utilsType = load(loader, PREFIX + "materials.MaterialListUtils");
            schedulerType = load(loader, PREFIX + "scheduler.TaskScheduler");
            countTaskType = load(loader, PREFIX + "scheduler.tasks.TaskCountBlocksPlacement");

            getPlacementManager = returning(method(dataManagerType, "getSchematicPlacementManager", true), managerType);
            getSelectedPlacement = returning(method(managerType, "getSelectedSchematicPlacement", false), placementType);
            getMaterialList = returning(method(placementType, "getMaterialList", false), materialListType);
            getName = returning(method(placementType, "getName", false), String.class);
            isEnabled = returning(method(placementType, "isEnabled", false), boolean.class);
            getMaterialsAll = returning(method(materialListType, "getMaterialsAll", false), List.class);
            getMultiplier = integerGetter(materialListType, "getMultiplier");
            reCreateMaterialList = returning(method(materialListType, "reCreateMaterialList", false), void.class);
            refreshPreFilteredList = returning(method(materialListType, "refreshPreFilteredList", false), void.class);
            recreateFilteredList = returning(method(materialListType, "recreateFilteredList", false), void.class);
            getStack = returning(method(entryType, "getStack", false), Object.class);
            getCountMissing = integerGetter(entryType, "getCountMissing");
            getCountTotal = integerGetter(entryType, "getCountTotal");
            getCountAvailable = integerGetter(entryType, "getCountAvailable");
            getInstanceClient = returning(method(schedulerType, "getInstanceClient", true), schedulerType);
            hasTask = returning(method(schedulerType, "hasTask", false, Class.class), boolean.class);
            ignoredField = ignoredField(materialListType);
        }

        Object selectedPlacement() {
            Object manager = required(getPlacementManager, null, getPlacementManager.getReturnType());
            Object placement = invoke(getSelectedPlacement, manager);
            if (placement != null && !placementType.isInstance(placement)) {
                throw unsupported("getSelectedSchematicPlacement 返回值不是 SchematicPlacement");
            }
            return placement;
        }

        Object materialListOf(Object placement) {
            return required(getMaterialList, placement, materialListType);
        }

        void check(Context context) {
            if (context == null || !placementType.isInstance(context.placement())
                    || !materialListType.isInstance(context.materialList())) {
                throw new BridgeException("Litematica 上下文无效：请先取得当前选中投影及其材料清单");
            }
        }

        List<?> materials(Object list) {
            return (List<?>) required(getMaterialsAll, list, List.class);
        }

        Set<?> ignored(Object list) {
            try {
                Object value = ignoredField.get(list);
                if (!(value instanceof Set<?> ignored)) {
                    throw unsupported("材料清单的 ignored 字段为空或不再是 Set");
                }
                return ignored;
            } catch (IllegalAccessException | IllegalArgumentException e) {
                throw new BridgeException("无法读取材料清单的忽略集合", e);
            }
        }

        Object clientScheduler() {
            return required(getInstanceClient, null, schedulerType);
        }

        boolean hasCountTask(Object scheduler) {
            return (Boolean) required(hasTask, scheduler, Boolean.class, countTaskType);
        }

        void updateAvailable(List<?> materials, Object player) {
            if (player == null) {
                throw new BridgeException("无法更新材料可用数量：客户端玩家尚未就绪");
            }
            AvailableKey key = new AvailableKey(materials.getClass(), player.getClass());
            Method update = availableMethods.computeIfAbsent(key, k ->
                    staticMethod(utilsType, "updateAvailableCounts", k.listType(), k.playerType()));
            invoke(update, null, materials, player);
        }

        Geo geo() {
            Geo result = geo;
            if (result != null) return result;
            synchronized (this) {
                if (geo != null) return geo;
                if (geoFailure != null) throw geoFailure;
                try {
                    result = new Geo(this);
                    geo = result;
                    return result;
                } catch (BridgeException e) {
                    geoFailure = e;
                    throw e;
                } catch (RuntimeException | LinkageError e) {
                    geoFailure = new BridgeException("Litematica 范围快照 API 初始化失败", e);
                    throw geoFailure;
                }
            }
        }
    }

    private record AvailableKey(Class<?> listType, Class<?> playerType) { }

    private static final class Geo {
        final Method getOrigin;
        final Method getRotation;
        final Method getMirror;
        final Method getSubRegions;
        final Method getMaterialListType;
        final Map<Class<?>, RegionApi> regions = new ConcurrentHashMap<>();
        final Map<Class<?>, Method> textMethods = new ConcurrentHashMap<>();
        private Layers layers;

        Geo(Api api) {
            getOrigin = returning(method(api.placementType, "getOrigin", false), Object.class);
            getRotation = enumGetter(api.placementType, "getRotation");
            getMirror = enumGetter(api.placementType, "getMirror");
            getSubRegions = returning(method(api.placementType, "getAllSubRegionsPlacements", false), Object.class);
            getMaterialListType = enumGetter(api.materialListType, "getMaterialListType");
        }

        String positionText(Object position) {
            Method text = textMethods.computeIfAbsent(position.getClass(), type -> {
                Method result = returning(method(type, "toString", false), String.class);
                if (result.getDeclaringClass() == Object.class) {
                    throw unsupported("位置对象没有结构化 toString，无法可靠检测坐标变化：" + type.getName());
                }
                return result;
            });
            return (String) required(text, position, String.class);
        }

        Layers layers(Api api) {
            if (layers == null) layers = new Layers(api);
            return layers;
        }
    }

    private static final class RegionApi {
        final Method getName;
        final Method getPos;
        final Method getRotation;
        final Method getMirror;
        final Method isEnabled;

        RegionApi(Class<?> type) {
            getName = returning(method(type, "getName", false), String.class);
            getPos = returning(method(type, "getPos", false), Object.class);
            getRotation = enumGetter(type, "getRotation");
            getMirror = enumGetter(type, "getMirror");
            isEnabled = returning(method(type, "isEnabled", false), boolean.class);
        }
    }

    private static final class Layers {
        final Method getRenderLayerRange;
        final Class<?> rangeType;
        final Method getLayerMode;
        final Method getAxis;
        final Method getLayerMin;
        final Method getLayerMax;

        Layers(Api api) {
            getRenderLayerRange = returning(method(api.dataManagerType, "getRenderLayerRange", true), Object.class);
            rangeType = getRenderLayerRange.getReturnType();
            getLayerMode = enumGetter(rangeType, "getLayerMode");
            getAxis = enumGetter(rangeType, "getAxis");
            getLayerMin = integerGetter(rangeType, "getLayerMin");
            getLayerMax = integerGetter(rangeType, "getLayerMax");
        }
    }

    private static Class<?> load(ClassLoader loader, String name) {
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            throw new BridgeException("不支持当前 Litematica API：无法加载 " + name, e);
        }
    }

    private static Method method(Class<?> owner, String name, boolean requireStatic, Class<?>... parameters) {
        try {
            Method result = owner.getMethod(name, parameters);
            if (Modifier.isStatic(result.getModifiers()) != requireStatic) {
                throw unsupported(owner.getName() + "." + name + " 的静态/实例签名不符合约定");
            }
            return result;
        } catch (NoSuchMethodException e) {
            throw new BridgeException("不支持当前 Litematica API：缺少公开方法 "
                    + owner.getName() + "." + name + "（参数数量 " + parameters.length + "）", e);
        }
    }

    private static Method returning(Method method, Class<?> expected) {
        Class<?> actual = method.getReturnType();
        boolean matches = expected == void.class ? actual == void.class
                : expected == boolean.class ? actual == boolean.class || actual == Boolean.class
                : !actual.isPrimitive() && expected.isAssignableFrom(actual);
        if (!matches) {
            throw unsupported(label(method) + " 的返回类型是 " + actual.getTypeName()
                    + "，预期 " + expected.getTypeName());
        }
        return method;
    }

    private static Method integerGetter(Class<?> owner, String name) {
        Method result = method(owner, name, false);
        Class<?> type = result.getReturnType();
        if (type != byte.class && type != short.class && type != int.class && type != long.class
                && type != Byte.class && type != Short.class && type != Integer.class && type != Long.class) {
            throw unsupported(label(result) + " 的返回类型不是受支持的整数类型：" + type.getTypeName());
        }
        return result;
    }

    private static Method enumGetter(Class<?> owner, String name) {
        Method result = method(owner, name, false);
        if (!result.getReturnType().isEnum()) {
            throw unsupported(label(result) + " 的返回类型不是枚举");
        }
        return result;
    }

    /** Matches by runtime assignability so intermediary class names are irrelevant. */
    private static Method staticMethod(Class<?> owner, String name, Class<?>... arguments) {
        List<Method> candidates = new ArrayList<>();
        for (Method candidate : owner.getMethods()) {
            if (!candidate.getName().equals(name) || !Modifier.isStatic(candidate.getModifiers())
                    || candidate.getReturnType() != void.class || candidate.isBridge()
                    || candidate.getParameterCount() != arguments.length) {
                continue;
            }
            Class<?>[] parameters = candidate.getParameterTypes();
            boolean matches = true;
            for (int i = 0; i < parameters.length; i++) {
                if (!parameters[i].isAssignableFrom(arguments[i])) {
                    matches = false;
                    break;
                }
            }
            if (matches) candidates.add(candidate);
        }
        List<Method> mostSpecific = new ArrayList<>();
        for (Method candidate : candidates) {
            boolean shadowed = false;
            for (Method other : candidates) {
                if (moreSpecific(other, candidate)) {
                    shadowed = true;
                    break;
                }
            }
            if (!shadowed) mostSpecific.add(candidate);
        }
        if (mostSpecific.size() != 1) {
            throw unsupported(owner.getName() + "." + name + " 无法唯一匹配 public static void 方法（候选 "
                    + mostSpecific.size() + "）");
        }
        return mostSpecific.getFirst();
    }

    private static boolean moreSpecific(Method candidate, Method other) {
        Class<?>[] first = candidate.getParameterTypes();
        Class<?>[] second = other.getParameterTypes();
        boolean stricter = false;
        for (int i = 0; i < first.length; i++) {
            if (!second[i].isAssignableFrom(first[i])) return false;
            stricter |= first[i] != second[i];
        }
        return stricter;
    }

    private static Field ignoredField(Class<?> materialListType) {
        for (Class<?> owner = materialListType; owner != null; owner = owner.getSuperclass()) {
            try {
                Field result = owner.getDeclaredField("ignored");
                if (Modifier.isStatic(result.getModifiers()) || !Set.class.isAssignableFrom(result.getType())) {
                    throw unsupported(owner.getName() + ".ignored 不再是实例 Set 字段");
                }
                if (!result.trySetAccessible()) {
                    throw unsupported(owner.getName() + ".ignored 无法访问");
                }
                return result;
            } catch (NoSuchFieldException ignored) {
                // May be inherited; keep walking up the hierarchy.
            }
        }
        throw unsupported(materialListType.getName() + " 及父类缺少 ignored 字段");
    }

    private static Object invoke(Method method, Object receiver, Object... arguments) {
        try {
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof VirtualMachineError fatal) throw fatal;
            throw new BridgeException("调用 " + label(method) + " 失败：" + describeFailure(cause), cause);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            throw new BridgeException("无法调用 " + label(method) + "：" + describeFailure(e), e);
        }
    }

    private static Object required(Method method, Object receiver, Class<?> expected, Object... arguments) {
        Object value = invoke(method, receiver, arguments);
        if (value == null || !expected.isInstance(value)) {
            throw unsupported(label(method) + " 返回空值或类型不符，预期 " + expected.getTypeName());
        }
        return value;
    }

    private static long integer(Method method, Object receiver) {
        Object value = invoke(method, receiver);
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)) {
            throw unsupported(label(method) + " 返回空值或非整数");
        }
        return ((Number) value).longValue();
    }

    private static long nonNegative(Method method, Object receiver) {
        long value = integer(method, receiver);
        if (value < 0L) {
            throw new BridgeException("材料数量无效：" + label(method) + " 返回负数 " + value);
        }
        return value;
    }

    private static long positive(Method method, Object receiver) {
        long value = integer(method, receiver);
        if (value < 1L) {
            throw new BridgeException("材料倍数无效：getMultiplier 返回 " + value);
        }
        return value;
    }

    private static long saturatedMultiply(long first, long second) {
        if (second == 0L) return 0L;
        return first > Long.MAX_VALUE / second ? Long.MAX_VALUE : first * second;
    }

    private static String label(Method method) {
        return method.getDeclaringClass().getName() + "." + method.getName();
    }

    private static BridgeException unsupported(String reason) {
        return new BridgeException("不支持当前 Litematica API：" + reason);
    }
}
