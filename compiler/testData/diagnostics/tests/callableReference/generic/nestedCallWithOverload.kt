// !WITH_NEW_INFERENCE
// !DIAGNOSTICS: -UNUSED_VARIABLE, -UNUSED_PARAMETER

fun foo(i: Int) {}
fun foo(s: String) {}
fun <K> id1(x: K): K = x
fun <K> id(x: K): K = x
fun <L> id2(x: L): L = x
fun <T> baz(x: T, y: T): T = TODO()

fun test() {
    val x1: (Int) -> Unit = id(id(::foo))
    val x2: (Int) -> Unit = baz(id(::foo), ::foo)
    val x3: (Int) -> Unit = baz(id(::foo), id(id(::foo)))
    val x4: (String) -> Unit = baz(id(::foo), id(id(::foo)))
    val x5: (Double) -> Unit = baz(id(::<!NI;CALLABLE_REFERENCE_RESOLUTION_AMBIGUITY, OI;NONE_APPLICABLE!>foo<!>), id(id(::<!NI;CALLABLE_REFERENCE_RESOLUTION_AMBIGUITY, OI;NONE_APPLICABLE!>foo<!>)))


    id<(Int) -> Unit>(id(id(::foo)))
    id(id<(Int) -> Unit>(::foo))
    baz<(Int) -> Unit>(id(::foo), id(id(::foo)))
    baz(id(::foo), id(id<(Int) -> Unit>(::foo)))
    baz(id(::foo), id<(Int) -> Unit>(id(::foo)))

    baz(id { it.inv() }, id<(Int) -> Unit> { })
    baz(id1 { x -> x.inv() }, id2 { <!NI;UNUSED_ANONYMOUS_PARAMETER!>x<!>: Int -> })
    baz(id1 { it.inv() }, id2 { <!NI;UNUSED_ANONYMOUS_PARAMETER!>x<!>: Int -> })
}
