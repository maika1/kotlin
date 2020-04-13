// !WITH_NEW_INFERENCE
// !DIAGNOSTICS: -UNUSED_LAMBDA_EXPRESSION, -UNUSED_EXPRESSION

fun case1(x: Any){
    when (x){
        1 -> try { {"1"}; ""; 1} catch (e: Exception) { { }} // type mismatch
        "1" -> try { 1 } catch (e: Exception) { { }} // type mismatch
        else -> try { 1 } catch (e: Exception) { {1 }} // type mismatch
    }

    when (x){
        1 -> try { {"1"}; ""} catch (e: Exception) { { }} // ok
        "1" -> try { 1 } catch (e: Exception) { { }} // ok
        else -> try { 1 } catch (e: Exception) { {1 }} // ok
    }
}

fun case2(x: Any){
    when (x){
        1 -> try { {"1"}; ""; TODO()} catch (e: Exception) { { }} // type mismatch
        "1" -> try { 1 } catch (e: Exception) { { }} // type mismatch
        else -> try { 1 } catch (e: Exception) { {1 }} // type mismatch
    }
    when (x){
        1 -> try { {"1"}; ""; TODO(); <!NI;UNREACHABLE_CODE!>""<!>} catch (e: Exception) { { }} // ok
        "1" -> try { 1 } catch (e: Exception) { { }} // ok
        else -> try { 1 } catch (e: Exception) { {1 }} // ok
    }
    when (x){
        1 -> try { {"1"}; ""; TODO()} catch (e: Exception) { { }} // ok
        "1" -> try { 1; "" } catch (e: Exception) { { }} // ok
        else -> try { 1 } catch (e: Exception) { {1 }} // ok
    }
}

fun case3(x: Any){
    when (x){
        1 -> try { {"1"}} catch (e: Exception) { { }} // type mismatch
        "1" -> try { 1 } catch (e: Exception) { { }} // type mismatch
        else -> try { 1 } catch (e: Exception) { {1 }} // type mismatch
    }
}