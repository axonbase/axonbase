package com.axonbase.core.engine;

import com.axonbase.common.AxonError;
import com.axonbase.value.AxonValue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Biblioteca de funções built-in da AxonQL.
 *
 * <p>Os nomes seguem a convenção de namespace do SurrealQL
 * ({@code string::uppercase}, {@code math::round}, {@code array::distinct}) para
 * que quem já conhece a linguagem não precise reaprender nada. As funções são
 * puras: recebem valores já avaliados e devolvem um {@link AxonValue}.</p>
 */
public final class Functions {

    private Functions() {
    }

    /** Constantes acessíveis sem parênteses, como {@code math::pi}. */
    public static AxonValue constant(String name) {
        return switch (name) {
            case "math::pi" -> AxonValue.num(Math.PI);
            case "math::e" -> AxonValue.num(Math.E);
            case "math::tau" -> AxonValue.num(Math.TAU);
            case "math::inf" -> AxonValue.num(Double.MAX_VALUE);
            case "math::neg_inf" -> AxonValue.num(-Double.MAX_VALUE);
            case "time::now" -> AxonValue.datetime(Instant.now());
            case "rand::uuid" -> AxonValue.str(UUID.randomUUID().toString());
            default -> null;
        };
    }

    /** Indica se o nome corresponde a uma função conhecida. */
    public static boolean exists(String name) {
        return NAMES.contains(name);
    }

    /**
     * Invoca uma função pelo nome. Devolve {@code null} quando a função não
     * existe, para o chamador poder decidir o que fazer.
     */
    public static AxonValue call(String name, List<AxonValue> args) {
        return switch (name) {
            // ----------------------------------------------------------
            // string
            // ----------------------------------------------------------
            case "string::concat" -> AxonValue.str(join(args, ""));
            case "string::contains" -> AxonValue.bool(str(arg(args, 0)).contains(str(arg(args, 1))));
            case "string::ends_with" -> AxonValue.bool(str(arg(args, 0)).endsWith(str(arg(args, 1))));
            case "string::starts_with" -> AxonValue.bool(str(arg(args, 0)).startsWith(str(arg(args, 1))));
            case "string::join" -> AxonValue.str(joinWithSeparator(args));
            case "string::len", "string::length" -> AxonValue.num(str(arg(args, 0)).length());
            case "string::lowercase" -> AxonValue.str(str(arg(args, 0)).toLowerCase());
            case "string::uppercase" -> AxonValue.str(str(arg(args, 0)).toUpperCase());
            case "string::repeat" -> AxonValue.str(str(arg(args, 0)).repeat(Math.max(0, (int) lng(arg(args, 1)))));
            case "string::replace" -> AxonValue.str(str(arg(args, 0))
                .replace(str(arg(args, 1)), str(arg(args, 2))));
            case "string::reverse" -> AxonValue.str(new StringBuilder(str(arg(args, 0))).reverse().toString());
            case "string::slice" -> AxonValue.str(slice(str(arg(args, 0)), args));
            case "string::split" -> AxonValue.array(strings(
                List.of(str(arg(args, 0)).split(java.util.regex.Pattern.quote(str(arg(args, 1))), -1))));
            case "string::trim" -> AxonValue.str(str(arg(args, 0)).trim());
            case "string::words" -> AxonValue.array(strings(
                List.of(str(arg(args, 0)).trim().split("\\s+"))));
            case "string::matches" -> AxonValue.bool(str(arg(args, 0)).matches(str(arg(args, 1))));
            case "string::slug" -> AxonValue.str(slug(str(arg(args, 0))));
            case "string::capitalize" -> AxonValue.str(capitalize(str(arg(args, 0))));
            case "string::pad_start" -> AxonValue.str(pad(str(arg(args, 0)), args, true));
            case "string::pad_end" -> AxonValue.str(pad(str(arg(args, 0)), args, false));

            // ----------------------------------------------------------
            // math
            // ----------------------------------------------------------
            case "math::abs" -> AxonValue.num(dec(arg(args, 0)).abs());
            case "math::ceil" -> AxonValue.num((long) Math.ceil(dbl(arg(args, 0))));
            case "math::floor" -> AxonValue.num((long) Math.floor(dbl(arg(args, 0))));
            case "math::round" -> AxonValue.num(Math.round(dbl(arg(args, 0))));
            case "math::fixed" -> AxonValue.num(dec(arg(args, 0))
                .setScale((int) lng(arg(args, 1)), RoundingMode.HALF_UP));
            case "math::sqrt" -> AxonValue.num(Math.sqrt(dbl(arg(args, 0))));
            case "math::pow" -> AxonValue.num(Math.pow(dbl(arg(args, 0)), dbl(arg(args, 1))));
            case "math::ln" -> AxonValue.num(Math.log(dbl(arg(args, 0))));
            case "math::log" -> AxonValue.num(Math.log(dbl(arg(args, 0))) / Math.log(dbl(arg(args, 1))));
            case "math::log10" -> AxonValue.num(Math.log10(dbl(arg(args, 0))));
            case "math::log2" -> AxonValue.num(Math.log(dbl(arg(args, 0))) / Math.log(2));
            case "math::exp" -> AxonValue.num(Math.exp(dbl(arg(args, 0))));
            case "math::sign" -> AxonValue.num(dec(arg(args, 0)).signum());
            case "math::clamp" -> AxonValue.num(Math.max(dbl(arg(args, 1)),
                Math.min(dbl(arg(args, 2)), dbl(arg(args, 0)))));
            case "math::min" -> reduceNumbers(args, true);
            case "math::max" -> reduceNumbers(args, false);
            case "math::sum" -> AxonValue.num(sumOf(numbers(args)));
            case "math::mean" -> mean(numbers(args));
            case "math::median" -> median(numbers(args));
            case "math::product" -> product(numbers(args));

            // ----------------------------------------------------------
            // agregações genéricas sobre coleções
            // ----------------------------------------------------------
            case "count" -> AxonValue.num(args.isEmpty() ? 0 : items(arg(args, 0)).size());
            case "sum" -> AxonValue.num(sumOf(numbers(args)));
            case "avg" -> mean(numbers(args));
            case "min" -> reduceNumbers(args, true);
            case "max" -> reduceNumbers(args, false);

            // ----------------------------------------------------------
            // array
            // ----------------------------------------------------------
            case "array::add" -> arrayAdd(arg(args, 0), arg(args, 1));
            case "array::append", "array::push" -> arrayPush(arg(args, 0), arg(args, 1));
            case "array::prepend" -> arrayPrepend(arg(args, 0), arg(args, 1));
            case "array::all" -> AxonValue.bool(items(arg(args, 0)).stream().allMatch(Functions::truthy));
            case "array::any" -> AxonValue.bool(items(arg(args, 0)).stream().anyMatch(Functions::truthy));
            case "array::at" -> at(items(arg(args, 0)), (int) lng(arg(args, 1)));
            case "array::concat" -> AxonValue.array(concat(items(arg(args, 0)), items(arg(args, 1))));
            case "array::complement" -> AxonValue.array(items(arg(args, 0)).stream()
                .filter(v -> !items(arg(args, 1)).contains(v)).toList());
            case "array::difference" -> AxonValue.array(difference(items(arg(args, 0)), items(arg(args, 1))));
            case "array::distinct" -> AxonValue.array(items(arg(args, 0)).stream().distinct().toList());
            case "array::find_index" -> AxonValue.num(items(arg(args, 0)).indexOf(arg(args, 1)));
            case "array::first" -> at(items(arg(args, 0)), 0);
            case "array::last" -> at(items(arg(args, 0)), items(arg(args, 0)).size() - 1);
            case "array::flatten" -> AxonValue.array(flatten(items(arg(args, 0))));
            case "array::insert" -> insert(items(arg(args, 0)), arg(args, 1), args);
            case "array::intersect" -> AxonValue.array(items(arg(args, 0)).stream()
                .filter(items(arg(args, 1))::contains).distinct().toList());
            case "array::join" -> AxonValue.str(joinWithSeparator(args));
            case "array::len", "array::length" -> AxonValue.num(items(arg(args, 0)).size());
            case "array::max" -> items(arg(args, 0)).stream().max(Comparator.naturalOrder()).orElse(AxonValue.nul());
            case "array::min" -> items(arg(args, 0)).stream().min(Comparator.naturalOrder()).orElse(AxonValue.nul());
            case "array::pop" -> pop(items(arg(args, 0)));
            case "array::remove" -> remove(items(arg(args, 0)), (int) lng(arg(args, 1)));
            case "array::reverse" -> AxonValue.array(reversed(items(arg(args, 0))));
            case "array::shuffle" -> AxonValue.array(shuffled(items(arg(args, 0))));
            case "array::slice" -> AxonValue.array(sliceList(items(arg(args, 0)), args));
            case "array::sort" -> AxonValue.array(sorted(items(arg(args, 0)), false));
            case "array::sort::asc" -> AxonValue.array(sorted(items(arg(args, 0)), false));
            case "array::sort::desc" -> AxonValue.array(sorted(items(arg(args, 0)), true));
            case "array::union" -> AxonValue.array(concat(items(arg(args, 0)), items(arg(args, 1)))
                .stream().distinct().toList());

            // ----------------------------------------------------------
            // object
            // ----------------------------------------------------------
            case "object::keys" -> AxonValue.array(strings(new ArrayList<>(obj(arg(args, 0)).keySet())));
            case "object::values" -> AxonValue.array(new ArrayList<>(obj(arg(args, 0)).values()));
            case "object::len", "object::length" -> AxonValue.num(obj(arg(args, 0)).size());
            case "object::entries" -> AxonValue.array(entries(obj(arg(args, 0))));
            case "object::from_entries" -> fromEntries(items(arg(args, 0)));

            // ----------------------------------------------------------
            // type e conversões
            // ----------------------------------------------------------
            case "type::bool" -> AxonValue.bool(truthy(arg(args, 0)));
            case "type::int" -> AxonValue.num(lng(arg(args, 0)));
            case "type::float", "type::number" -> AxonValue.num(dbl(arg(args, 0)));
            case "type::decimal" -> AxonValue.num(dec(arg(args, 0)));
            case "type::string" -> AxonValue.str(str(arg(args, 0)));
            case "type::datetime" -> AxonValue.datetime(instant(arg(args, 0)));
            case "type::table" -> AxonValue.table(str(arg(args, 0)));
            case "type::record" -> AxonValue.record(str(arg(args, 0)), AxonValue.str(str(arg(args, 1))));
            case "type::array" -> AxonValue.array(items(arg(args, 0)));
            case "type::is::array" -> AxonValue.bool(arg(args, 0).isArray());
            case "type::is::bool" -> AxonValue.bool(arg(args, 0).isBool());
            case "type::is::datetime" -> AxonValue.bool(arg(args, 0).isDatetime());
            case "type::is::none" -> AxonValue.bool(arg(args, 0).isNone());
            case "type::is::null" -> AxonValue.bool(arg(args, 0).isNull());
            case "type::is::number" -> AxonValue.bool(arg(args, 0).isNumber());
            case "type::is::object" -> AxonValue.bool(arg(args, 0).isObject());
            case "type::is::record" -> AxonValue.bool(arg(args, 0).isRecordId());
            case "type::is::string" -> AxonValue.bool(arg(args, 0).isString());

            // ----------------------------------------------------------
            // record / meta
            // ----------------------------------------------------------
            case "record::id", "meta::id" -> recordId(arg(args, 0));
            case "record::table", "record::tb", "meta::tb" -> recordTable(arg(args, 0));
            case "record::exists" -> AxonValue.bool(arg(args, 0).isRecordId());

            // geo (GeoJSON) e vetores
            case "geometry::point" -> GeoVector.point(arg(args, 0), arg(args, 1));
            case "geometry::line" -> GeoVector.line(arg(args, 0));
            case "geometry::polygon" -> GeoVector.polygon(arg(args, 0));
            case "geo::distance" -> GeoVector.distance(arg(args, 0), arg(args, 1));
            case "geo::area" -> GeoVector.area(arg(args, 0));
            case "geo::contains" -> GeoVector.contains(arg(args, 0), arg(args, 1));
            case "vector::distance::euclidean" -> GeoVector.euclidean(arg(args, 0), arg(args, 1));
            case "vector::distance::manhattan" -> GeoVector.manhattan(arg(args, 0), arg(args, 1));
            case "vector::similarity::cosine" -> GeoVector.cosine(arg(args, 0), arg(args, 1));

            // ----------------------------------------------------------
            // time
            // ----------------------------------------------------------
            case "time::now" -> AxonValue.datetime(Instant.now());
            case "time::unix" -> AxonValue.num(instant(arg(args, 0)).getEpochSecond());
            case "time::millis" -> AxonValue.num(instant(arg(args, 0)).toEpochMilli());
            case "time::year" -> AxonValue.num(zoned(arg(args, 0)).getYear());
            case "time::month" -> AxonValue.num(zoned(arg(args, 0)).getMonthValue());
            case "time::day" -> AxonValue.num(zoned(arg(args, 0)).getDayOfMonth());
            case "time::hour" -> AxonValue.num(zoned(arg(args, 0)).getHour());
            case "time::minute" -> AxonValue.num(zoned(arg(args, 0)).getMinute());
            case "time::second" -> AxonValue.num(zoned(arg(args, 0)).getSecond());
            case "time::wday" -> AxonValue.num(zoned(arg(args, 0)).getDayOfWeek().getValue());
            case "time::yday" -> AxonValue.num(zoned(arg(args, 0)).getDayOfYear());
            case "time::from::unix" -> AxonValue.datetime(Instant.ofEpochSecond(lng(arg(args, 0))));
            case "time::from::millis" -> AxonValue.datetime(Instant.ofEpochMilli(lng(arg(args, 0))));

            // ----------------------------------------------------------
            // rand
            // ----------------------------------------------------------
            case "rand" -> AxonValue.num(ThreadLocalRandom.current().nextDouble());
            case "rand::bool" -> AxonValue.bool(ThreadLocalRandom.current().nextBoolean());
            case "rand::float" -> AxonValue.num(ThreadLocalRandom.current().nextDouble());
            case "rand::int" -> randomInt(args);
            case "rand::string" -> AxonValue.str(randomString(args));
            case "rand::uuid" -> AxonValue.str(UUID.randomUUID().toString());
            case "rand::enum" -> args.isEmpty() ? AxonValue.nul()
                : args.get(ThreadLocalRandom.current().nextInt(args.size()));

            // ----------------------------------------------------------
            // crypto e codificação
            // ----------------------------------------------------------
            case "crypto::md5" -> AxonValue.str(digest("MD5", str(arg(args, 0))));
            case "crypto::sha1" -> AxonValue.str(digest("SHA-1", str(arg(args, 0))));
            case "crypto::sha256" -> AxonValue.str(digest("SHA-256", str(arg(args, 0))));
            case "crypto::sha512" -> AxonValue.str(digest("SHA-512", str(arg(args, 0))));
            case "encoding::base64::encode" -> AxonValue.str(Base64.getEncoder()
                .encodeToString(str(arg(args, 0)).getBytes(StandardCharsets.UTF_8)));
            case "encoding::base64::decode" -> AxonValue.str(new String(
                Base64.getDecoder().decode(str(arg(args, 0))), StandardCharsets.UTF_8));

            // ----------------------------------------------------------
            // valores
            // ----------------------------------------------------------
            case "value::coalesce" -> args.stream()
                .filter(v -> !v.isNull() && !v.isNone()).findFirst().orElse(AxonValue.nul());
            case "value::default" -> arg(args, 0).isNull() || arg(args, 0).isNone()
                ? arg(args, 1) : arg(args, 0);

            default -> null;
        };
    }

    // ------------------------------------------------------------------
    // Auxiliares de argumentos e conversão
    // ------------------------------------------------------------------

    private static AxonValue arg(List<AxonValue> args, int i) {
        return i < args.size() ? args.get(i) : AxonValue.none();
    }

    private static String str(AxonValue v) {
        if (v == null || v.isNone() || v.isNull()) {
            return "";
        }
        return v.isString() ? v.asString() : v.toString();
    }

    private static long lng(AxonValue v) {
        if (v == null) {
            return 0;
        }
        if (v.isNumber()) {
            return v.asLong();
        }
        try {
            return Long.parseLong(str(v).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double dbl(AxonValue v) {
        if (v == null) {
            return 0;
        }
        if (v.isNumber()) {
            return v.asDouble();
        }
        try {
            return Double.parseDouble(str(v).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static BigDecimal dec(AxonValue v) {
        if (v != null && v.isNumber()) {
            return v.asDecimal();
        }
        try {
            return new BigDecimal(str(v).trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private static Map<String, AxonValue> obj(AxonValue v) {
        return v != null && v.isObject() ? v.asObject() : Map.of();
    }

    private static boolean truthy(AxonValue v) {
        if (v == null) {
            return false;
        }
        return switch (v.type()) {
            case BOOL -> v.asBool();
            case NULL, NONE -> false;
            case STRING -> !v.asString().isEmpty();
            case NUMBER -> v.asDouble() != 0;
            case ARRAY, SET -> !items(v).isEmpty();
            case OBJECT -> !v.asObject().isEmpty();
            default -> true;
        };
    }

    /** Elementos de um valor de coleção; um valor solto conta como lista de um. */
    private static List<AxonValue> items(AxonValue v) {
        if (v == null || v.isNone() || v.isNull()) {
            return List.of();
        }
        if (v.isArray()) {
            return v.asArray();
        }
        if (v.isSet()) {
            return v.asSet();
        }
        return List.of(v);
    }

    /** Números a somar/reduzir: aceita uma coleção ou vários argumentos. */
    private static List<AxonValue> numbers(List<AxonValue> args) {
        if (args.size() == 1) {
            return items(args.get(0));
        }
        return args;
    }

    private static List<AxonValue> strings(List<String> list) {
        return list.stream().map(AxonValue::str).toList();
    }

    private static String join(List<AxonValue> args, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
                sb.append(sep);
            }
            sb.append(str(args.get(i)));
        }
        return sb.toString();
    }

    /** {@code join(separador, ...partes)} com coleção ou argumentos soltos. */
    private static String joinWithSeparator(List<AxonValue> args) {
        if (args.isEmpty()) {
            return "";
        }
        String sep = str(args.get(0));
        List<AxonValue> parts = args.size() == 2 && (args.get(1).isArray() || args.get(1).isSet())
            ? items(args.get(1))
            : args.subList(1, args.size());
        return join(parts, sep);
    }

    private static String slice(String s, List<AxonValue> args) {
        int from = clampIndex((int) lng(arg(args, 1)), s.length());
        int len = args.size() > 2 ? (int) lng(arg(args, 2)) : s.length() - from;
        int to = clampIndex(from + Math.max(0, len), s.length());
        return s.substring(from, Math.max(from, to));
    }

    private static List<AxonValue> sliceList(List<AxonValue> list, List<AxonValue> args) {
        int from = clampIndex((int) lng(arg(args, 1)), list.size());
        int len = args.size() > 2 ? (int) lng(arg(args, 2)) : list.size() - from;
        int to = clampIndex(from + Math.max(0, len), list.size());
        return List.copyOf(list.subList(from, Math.max(from, to)));
    }

    private static int clampIndex(int i, int size) {
        if (i < 0) {
            i = size + i;
        }
        return Math.max(0, Math.min(size, i));
    }

    private static String slug(String s) {
        String normalized = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toLowerCase()
            .replaceAll("[^a-z0-9]+", "-");
        return normalized.replaceAll("^-+|-+$", "");
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String pad(String s, List<AxonValue> args, boolean start) {
        int size = (int) lng(arg(args, 1));
        String with = args.size() > 2 ? str(arg(args, 2)) : " ";
        if (with.isEmpty() || s.length() >= size) {
            return s;
        }
        StringBuilder sb = new StringBuilder();
        while (sb.length() < size - s.length()) {
            sb.append(with);
        }
        String fill = sb.substring(0, size - s.length());
        return start ? fill + s : s + fill;
    }

    // ------------------------------------------------------------------
    // Auxiliares numéricos
    // ------------------------------------------------------------------

    private static BigDecimal sumOf(List<AxonValue> values) {
        BigDecimal total = BigDecimal.ZERO;
        for (AxonValue v : values) {
            if (v.isNumber()) {
                total = total.add(v.asDecimal());
            }
        }
        return total;
    }

    private static AxonValue mean(List<AxonValue> values) {
        List<AxonValue> nums = values.stream().filter(AxonValue::isNumber).toList();
        if (nums.isEmpty()) {
            return AxonValue.nul();
        }
        return AxonValue.num(sumOf(nums).divide(BigDecimal.valueOf(nums.size()), 10, RoundingMode.HALF_UP)
            .stripTrailingZeros());
    }

    private static AxonValue median(List<AxonValue> values) {
        List<AxonValue> nums = new ArrayList<>(values.stream().filter(AxonValue::isNumber).toList());
        if (nums.isEmpty()) {
            return AxonValue.nul();
        }
        nums.sort(Comparator.naturalOrder());
        int mid = nums.size() / 2;
        if (nums.size() % 2 == 1) {
            return nums.get(mid);
        }
        return AxonValue.num(nums.get(mid - 1).asDecimal().add(nums.get(mid).asDecimal())
            .divide(BigDecimal.valueOf(2), 10, RoundingMode.HALF_UP).stripTrailingZeros());
    }

    private static AxonValue product(List<AxonValue> values) {
        BigDecimal total = BigDecimal.ONE;
        boolean any = false;
        for (AxonValue v : values) {
            if (v.isNumber()) {
                total = total.multiply(v.asDecimal());
                any = true;
            }
        }
        return any ? AxonValue.num(total) : AxonValue.nul();
    }

    private static AxonValue reduceNumbers(List<AxonValue> args, boolean minimum) {
        List<AxonValue> values = numbers(args).stream().filter(AxonValue::isNumber).toList();
        if (values.isEmpty()) {
            return AxonValue.nul();
        }
        return minimum
            ? values.stream().min(Comparator.naturalOrder()).orElse(AxonValue.nul())
            : values.stream().max(Comparator.naturalOrder()).orElse(AxonValue.nul());
    }

    private static AxonValue randomInt(List<AxonValue> args) {
        long min = args.isEmpty() ? 0 : lng(arg(args, 0));
        long max = args.size() > 1 ? lng(arg(args, 1)) : Integer.MAX_VALUE;
        if (max <= min) {
            return AxonValue.num(min);
        }
        return AxonValue.num(ThreadLocalRandom.current().nextLong(min, max + 1));
    }

    private static String randomString(List<AxonValue> args) {
        int len = args.isEmpty() ? 32 : (int) lng(arg(args, 0));
        String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.max(0, len); i++) {
            sb.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Auxiliares de coleção
    // ------------------------------------------------------------------

    private static AxonValue at(List<AxonValue> list, int index) {
        int i = index < 0 ? list.size() + index : index;
        return i >= 0 && i < list.size() ? list.get(i) : AxonValue.nul();
    }

    private static List<AxonValue> concat(List<AxonValue> a, List<AxonValue> b) {
        List<AxonValue> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    private static List<AxonValue> difference(List<AxonValue> a, List<AxonValue> b) {
        List<AxonValue> out = new ArrayList<>();
        a.stream().filter(v -> !b.contains(v)).forEach(out::add);
        b.stream().filter(v -> !a.contains(v)).forEach(out::add);
        return out;
    }

    private static List<AxonValue> flatten(List<AxonValue> list) {
        List<AxonValue> out = new ArrayList<>();
        for (AxonValue v : list) {
            if (v.isArray() || v.isSet()) {
                out.addAll(items(v));
            } else {
                out.add(v);
            }
        }
        return out;
    }

    private static AxonValue arrayAdd(AxonValue array, AxonValue value) {
        List<AxonValue> list = new ArrayList<>(items(array));
        if (!list.contains(value)) {
            list.add(value);
        }
        return AxonValue.array(list);
    }

    private static AxonValue arrayPush(AxonValue array, AxonValue value) {
        List<AxonValue> list = new ArrayList<>(items(array));
        list.add(value);
        return AxonValue.array(list);
    }

    private static AxonValue arrayPrepend(AxonValue array, AxonValue value) {
        List<AxonValue> list = new ArrayList<>(items(array));
        list.add(0, value);
        return AxonValue.array(list);
    }

    private static AxonValue insert(List<AxonValue> list, AxonValue value, List<AxonValue> args) {
        List<AxonValue> out = new ArrayList<>(list);
        int index = args.size() > 2 ? (int) lng(arg(args, 2)) : out.size();
        out.add(Math.max(0, Math.min(out.size(), index)), value);
        return AxonValue.array(out);
    }

    private static AxonValue pop(List<AxonValue> list) {
        if (list.isEmpty()) {
            return AxonValue.array(List.of());
        }
        return AxonValue.array(List.copyOf(list.subList(0, list.size() - 1)));
    }

    private static AxonValue remove(List<AxonValue> list, int index) {
        List<AxonValue> out = new ArrayList<>(list);
        int i = index < 0 ? out.size() + index : index;
        if (i >= 0 && i < out.size()) {
            out.remove(i);
        }
        return AxonValue.array(out);
    }

    private static List<AxonValue> reversed(List<AxonValue> list) {
        List<AxonValue> out = new ArrayList<>(list);
        java.util.Collections.reverse(out);
        return out;
    }

    private static List<AxonValue> shuffled(List<AxonValue> list) {
        List<AxonValue> out = new ArrayList<>(list);
        java.util.Collections.shuffle(out);
        return out;
    }

    private static List<AxonValue> sorted(List<AxonValue> list, boolean descending) {
        List<AxonValue> out = new ArrayList<>(list);
        out.sort(descending ? Comparator.reverseOrder() : Comparator.naturalOrder());
        return out;
    }

    private static List<AxonValue> entries(Map<String, AxonValue> map) {
        List<AxonValue> out = new ArrayList<>();
        map.forEach((k, v) -> out.add(AxonValue.array(List.of(AxonValue.str(k), v))));
        return out;
    }

    private static AxonValue fromEntries(List<AxonValue> entries) {
        Map<String, AxonValue> map = new LinkedHashMap<>();
        for (AxonValue e : entries) {
            List<AxonValue> pair = items(e);
            if (pair.size() >= 2) {
                map.put(str(pair.get(0)), pair.get(1));
            }
        }
        return AxonValue.object(map);
    }

    // ------------------------------------------------------------------
    // Auxiliares de record id, tempo e crypto
    // ------------------------------------------------------------------

    private static AxonValue recordId(AxonValue v) {
        if (v.isRecordId()) {
            Object key = v.asRecordId().key();
            return key instanceof AxonValue kv ? kv : AxonValue.str(String.valueOf(key));
        }
        String s = str(v);
        int i = s.indexOf(':');
        return AxonValue.str(i >= 0 ? s.substring(i + 1) : s);
    }

    private static AxonValue recordTable(AxonValue v) {
        if (v.isRecordId()) {
            return AxonValue.str(v.asRecordId().table());
        }
        String s = str(v);
        int i = s.indexOf(':');
        return AxonValue.str(i >= 0 ? s.substring(0, i) : "");
    }

    private static Instant instant(AxonValue v) {
        if (v == null) {
            return Instant.now();
        }
        if (v.isDatetime()) {
            return v.asInstant();
        }
        if (v.isNumber()) {
            return Instant.ofEpochSecond(v.asLong());
        }
        try {
            return Instant.parse(str(v));
        } catch (RuntimeException e) {
            return Instant.now();
        }
    }

    private static ZonedDateTime zoned(AxonValue v) {
        return instant(v).atZone(ZoneOffset.UTC);
    }

    private static String digest(String algorithm, String input) {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            byte[] out = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : out) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw AxonError.internal("algoritmo de hash não disponível: " + algorithm);
        }
    }

    /** Nomes conhecidos, para validação e para o INFO. */
    private static final java.util.Set<String> NAMES = java.util.Set.of(
        "string::concat", "string::contains", "string::ends_with", "string::starts_with",
        "string::join", "string::len", "string::length", "string::lowercase", "string::uppercase",
        "string::repeat", "string::replace", "string::reverse", "string::slice", "string::split",
        "string::trim", "string::words", "string::matches", "string::slug", "string::capitalize",
        "string::pad_start", "string::pad_end",
        "math::abs", "math::ceil", "math::floor", "math::round", "math::fixed", "math::sqrt",
        "math::pow", "math::ln", "math::log", "math::log10", "math::log2", "math::exp",
        "math::sign", "math::clamp", "math::min", "math::max", "math::sum", "math::mean",
        "math::median", "math::product",
        "count", "sum", "avg", "min", "max",
        "array::add", "array::append", "array::push", "array::prepend", "array::all", "array::any",
        "array::at", "array::concat", "array::complement", "array::difference", "array::distinct",
        "array::find_index", "array::first", "array::last", "array::flatten", "array::insert",
        "array::intersect", "array::join", "array::len", "array::length", "array::max", "array::min",
        "array::pop", "array::remove", "array::reverse", "array::shuffle", "array::slice",
        "array::sort", "array::sort::asc", "array::sort::desc", "array::union",
        "object::keys", "object::values", "object::len", "object::length", "object::entries",
        "object::from_entries",
        "type::bool", "type::int", "type::float", "type::number", "type::decimal", "type::string",
        "type::datetime", "type::table", "type::record", "type::array", "type::is::array",
        "type::is::bool", "type::is::datetime", "type::is::none", "type::is::null",
        "type::is::number", "type::is::object", "type::is::record", "type::is::string",
        "record::id", "record::table", "record::tb", "record::exists", "meta::id", "meta::tb",
        "geometry::point", "geometry::line", "geometry::polygon", "geo::distance", "geo::area",
        "geo::contains", "vector::distance::euclidean", "vector::distance::manhattan",
        "vector::similarity::cosine",
        "search::score", "search::highlight",
        "time::now", "time::unix", "time::millis", "time::year", "time::month", "time::day",
        "time::hour", "time::minute", "time::second", "time::wday", "time::yday",
        "time::from::unix", "time::from::millis",
        "rand", "rand::bool", "rand::float", "rand::int", "rand::string", "rand::uuid", "rand::enum",
        "crypto::md5", "crypto::sha1", "crypto::sha256", "crypto::sha512",
        "encoding::base64::encode", "encoding::base64::decode",
        "value::coalesce", "value::default");

    /** Lista ordenada dos nomes, usada pelo {@code INFO FOR ROOT}. */
    public static List<String> names() {
        return NAMES.stream().sorted().toList();
    }
}
