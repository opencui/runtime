package io.opencui.du


// For only the candidates that are returned from retrieval phase, we do nested matcher.
// There are two different places that we need nested matching:
// 1. Find the exact match. This requires us to test whether utterance and examplar are the same, and
//    at the same time keep the filling information. This matches against the exemplar on the container frame.
// 2. Find the slot frame match, this only matching the exemplar on the slot type.
//
// For now, we assume the tokenization is done by Lucene, for each token we also keep the character offset in
// the original utterance so that we can. 

/**
 * extractive matching of potentially nested expression. This is useful for simpler noun phrases
 * where the structure is relatively stable, and QA model alone is not a good indicate.
 * We will also use this for exact matching, where it is not as efficient as we like.
 */
class NestedMatcher {
    // A simple pipeline is needed for make

    //


}