# brave-propagation-w3c rationale

## Trace Context Specification

### Why do we write a tracestate entry?
We write both "traceparent" and a "tracestate" entry for two reasons. The first is due to
incompatibility between the "traceparent" format and our trace context. This is described in another
section.

The other reason is durability across hops. When your direct upstream is not the same tracing
system, its span ID (which they call parentId in section 3.2.2.4) will not be valid: using it will
break the trace. Instead, we look at our "tracestate" entry, which represents the last known place
in the same system.

### Why serialize the trace context in two formats?

The "traceparent" header is only portable to get the `TraceContext.traceId()` and
`TraceContext.spanId()`. Section 3.2.2.5.1, the sampled flag, is incompatible with B3 sampling. The
format also lacks fields for `TraceContext.parentId()` and `TraceContext.debug()`. This requires us
to re-serialize the same context in two formats: one for compliance ("traceparent") and one that
actually stores the context (B3 single format).

The impact on users will be higher overhead and confusion when comparing the sampled value of
"traceparent" which may be different than "b3".

### Why is traceparent incompatible with B3?

It may seem like incompatibility between "traceparent" and B3 were accidental, but that was not the
case. The Zipkin community assembled the first meetings leading to this specification, and were
directly involved in the initial design. Use cases of B3 were well known by working group members.
Choices to be incompatible with B3 (and Amazon X-Ray format) sampling were conscious, as were
decisions to omit other fields we use. These decisions were made in spite of protest from Zipkin
community members. There is a rationale document for the specification, but the working group has so
far not explained these decisions, or even mention B3 at all.

https://github.com/w3c/trace-context/blob/d2ed8084780efcedab7e60c48b06ca3c00bea55c/http_header_format_rationale.md
