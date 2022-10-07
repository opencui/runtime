
interface Tokenizer {
    fun tokenize(input: String, lang: String="en"): ArrayList<String>
}

class WhiteSpaceTokenizer() : Tokenizer {
    override fun tokenize(input: String, lang: String): ArrayList<String> {
        // TODO (@flora) handle "$XXX$,"
        return ArrayList(input.split(' '))
    }
}

