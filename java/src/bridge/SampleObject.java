package bridge;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic fixture for live JVM integration tests of NEW_INSTANCE,
 * INVOKE_INSTANCE_METHOD, GET_FIELD, SET_FIELD, RELEASE_OBJECT and
 * IntArray/StrArray argument passing. Available on both host JVM and device.
 */
public class SampleObject {
    public int counter = 42;
    private String name = "hello";
    public int[] numbers = new int[]{1, 2, 3};
    public String[] words = new String[]{"a", "b"};

    public SampleObject() {}

    public SampleObject(String name, int counter) {
        this.name = name;
        this.counter = counter;
    }

    public int increment(int delta) {
        counter += delta;
        return counter;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int sumInts(int[] arr) {
        int s = 0;
        if (arr != null) {
            for (int v : arr) {
                s += v;
            }
        }
        return s;
    }

    public String joinStrings(String[] arr) {
        if (arr == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(arr[i]);
        }
        return sb.toString();
    }

    public List<String> makeList(String a, String b) {
        List<String> l = new ArrayList<>();
        l.add(a);
        l.add(b);
        return l;
    }

    public Object getSelf() {
        return this;
    }
}
