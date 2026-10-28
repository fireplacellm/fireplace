# Attribution

Fireplace is our own code. It builds on open components, each credited here. All source
is original to this project; no code is copied from any other project.

## Engine & models

| Component | Project | License | Use |
|---|---|---|---|
| Inference engine | llama.cpp (ggml-org) | MIT | linked; our own JNI wrapper |
| Model: Qwen2.5 0.5B / 1.5B / 3B Instruct | Qwen (`Qwen/Qwen2.5-*-Instruct-GGUF`) | Apache-2.0 | GGUF, Q4_K_M, on-device |
| Model: Gemma 2 2B IT | Google (`bartowski/gemma-2-2b-it-GGUF`) | Gemma Terms of Use | GGUF, Q4_K_M, optional |
| Model: Qwen3 30B-A3B | Qwen (`unsloth/Qwen3-30B-A3B-GGUF`) | Apache-2.0 | MoE, experts stream from flash (12 GB tier) |
| Embedder | all-MiniLM-L6-v2 (sentence-transformers) | Apache-2.0 | ONNX int8, dense retrieval |

Speculative decoding is **prompt-lookup** (lossless, no draft model). Model licenses are
pinned by HuggingFace repo above; each GGUF is verified by SHA-256 on download.

## Data sources (knowledge packs)

Each pack is rebuilt by us from the primary public dataset below with our own pipeline
(`corpus/`); see `corpus/sources.json`.

| Pack | Source | License |
|---|---|---|
| wikipedia | `wikimedia/wikipedia` (Hugging Face) | CC BY-SA 4.0 |
| first-aid | Wikipedia medical / first-aid articles | CC BY-SA 4.0 |
| crypto | Wikipedia crypto / web3 articles | CC BY-SA 4.0 |
| travel | Wikivoyage `yuiseki/wikivoyage-geotagged` + GeoNames cities1000 | CC BY-SA 4.0 / CC BY 4.0 |
| arxiv | arXiv abstracts + metadata (`Cornell-University/arxiv`) | CC0 (metadata) |

## Explicitly NOT used

- No third-party project's prebuilt dataset, model file, index, reranker, or code.
- No third-party repository is a dependency, submodule, or fork of Fireplace.
