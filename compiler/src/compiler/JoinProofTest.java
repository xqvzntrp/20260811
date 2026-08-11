package compiler;

import java.util.Set;

/** Focused executable checks for the semantic kernel. */
public final class JoinProofTest {
    private JoinProofTest() {}

    public static void main(String[] args) {
        JoinProof forward = new JoinProof("ORDER", "ORDERKEY");
        forward.join("ORDER", "ORDERKEY", "CUSTOMER", "CUSTOMERKEY", "#MANY_TO_ONE");
        require(forward.canGroupBy("CUSTOMERKEY"), "forward join should determine customer key");
        require(forward.aggregateIsSafe(Set.of("CUSTOMERKEY"), "ORDERKEY"),
                "customer revenue aggregate should be safe");

        JoinProof fanout = new JoinProof("CUSTOMER", "CUSTOMERKEY");
        fanout.join("ORDER", "ORDERKEY", "CUSTOMER", "CUSTOMERKEY", "#MANY_TO_ONE");
        fanout.join("TICKET", "TICKETKEY", "CUSTOMER", "CUSTOMERKEY", "#MANY_TO_ONE");
        require(!fanout.aggregateIsSafe(Set.of("CUSTOMERKEY"), "ORDERKEY"),
                "ticket branch must make order aggregate unsafe");
        require(fanout.uncontrolledKeys(Set.of("CUSTOMERKEY"), "ORDERKEY").equals(Set.of("TICKETKEY")),
                "fanout proof should identify the uncontrolled ticket key");
        System.out.println("JoinProofTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
