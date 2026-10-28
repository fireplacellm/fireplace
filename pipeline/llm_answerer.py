"""A real-model Answerer that runs llama.cpp on the host for pre-device validation.

This shells out to the `llama-completion` binary (built from the pinned
llama.cpp) with the grounded prompt from answerer.build_prompt, applying the
model's chat template manually so the output is deterministic and parseable.

It exists so we can validate the WHOLE pipeline with a genuine LLM before the
device (the "simulator-first" step). On device the identical grounded prompt
goes through the JNI engine (engine/.../LlamaEngine.kt) instead of a subprocess;
the Answerer interface is the same, so nothing else changes.
"""

from __future__ import annotations

import os
import re
import subprocess

from answerer import SYSTEM_PROMPT, build_prompt
from reader import Hit

_THINK = re.compile(r"<think>.*?</think>", re.DOTALL | re.IGNORECASE)
_CITE_MARK = re.compile(r"\[\d+]")


def _strip_think(text: str) -> str:
    """Remove any Qwen <think>…</think> reasoning block (defense in depth)."""
    return _THINK.sub("", text).replace("<think>", "").replace("</think>", "")


def _tidy(text: str) -> str:
    """Mirror of Kotlin Prompt.tidy: strip reasoning, drop content-duplicate
    sentences (small models loop at length, re-citing the same sentence with a
    new [n]), and trim a trailing half-sentence from hitting the token cap."""
    stripped = _strip_think(text).strip()
    seen: set[str] = set()
    paragraphs: list[str] = []
    for para in re.split(r"\n\s*\n", stripped):
        kept: list[str] = []
        for s in [p for p in re.split(r"(?<=[.!?])\s+", para) if p.strip()]:
            key = _CITE_MARK.sub("", s).strip().lower()
            if len(key) > 8 and key in seen:
                continue
            if len(key) > 8:
                seen.add(key)
            kept.append(s)
        if kept:
            paragraphs.append(" ".join(kept).strip())
    t = "\n\n".join(paragraphs).strip()
    if t and t[-1] not in '.!?")]':
        cut = max(t.rfind("."), t.rfind("!"), t.rfind("?"))
        if cut > 40:
            t = t[: cut + 1]
    return t

# Qwen2.5 / Qwen3 chat template markers.
_IM_START = "<|im_start|>"
_IM_END = "<|im_end|>"


def _default(path_env: str, rel: str) -> str:
    return os.environ.get(path_env) or os.path.join(
        os.path.dirname(__file__), "..", rel
    )


class LlamaCppAnswerer:
    def __init__(self, binary: str | None = None, model: str | None = None,
                 *, n_predict: int = 640, ctx: int = 4096, temp: float = 0.3,
                 timeout_s: int = 180):
        self.binary = binary or _default(
            "FIREPLACE_LLAMA_BIN",
            "engine/src/main/cpp/llama.cpp/build/bin/llama-completion",
        )
        self.model = model or _default(
            "FIREPLACE_MODEL", "models/qwen2.5-0.5b-instruct-q4_k_m.gguf"
        )
        self.n_predict, self.ctx, self.temp, self.timeout_s = n_predict, ctx, temp, timeout_s

    def available(self) -> bool:
        return os.path.exists(self.binary) and os.path.exists(self.model)

    def answer(self, question: str, hits: list[Hit]) -> str:
        # build_prompt already fences sources and neutralizes control tokens.
        body = build_prompt(question, hits)
        # Split the system instruction from the sources+question body.
        sources_and_q = body.split(SYSTEM_PROMPT, 1)[-1].strip()
        # Qwen3 is a reasoning model; "/no_think" disables the <think> block so we
        # get a direct grounded answer at phone-usable latency (not minutes of
        # reasoning). Thinking tokens would also blow the output budget.
        chat = (
            f"{_IM_START}system\n{SYSTEM_PROMPT} /no_think{_IM_END}\n"
            # One compact example: teaches the developed-prose + inline [n] shape
            # without bloating the prompt (prefill cost matters on slow devices).
            f"{_IM_START}user\nSources:\n"
            "[1] (Eiffel Tower) A wrought-iron lattice tower in Paris, France, built for the 1889 World's Fair; at 330 m it was the world's tallest structure until 1930.\n\n"
            f"Question: Tell me about the Eiffel Tower.{_IM_END}\n"
            f"{_IM_START}assistant\nThe Eiffel Tower is a wrought-iron lattice tower in Paris, France, built for the 1889 World's Fair [1]. "
            f"Standing 330 metres tall, it was the tallest structure in the world until 1930 [1].{_IM_END}\n"
            f"{_IM_START}user\n{sources_and_q}{_IM_END}\n"
            f"{_IM_START}assistant\n"
        )
        out = self._run(chat)
        return _tidy(self._extract(out))

    def _run(self, prompt: str) -> str:
        proc = subprocess.run(
            [self.binary, "-m", self.model, "-p", prompt, "-n", str(self.n_predict),
             "-c", str(self.ctx), "--temp", str(self.temp), "-no-cnv"],
            capture_output=True, text=True, timeout=self.timeout_s,
        )
        return proc.stdout

    @staticmethod
    def _extract(stdout: str) -> str:
        # llama-completion echoes the templated prompt, rendering the chat
        # special tokens as plain role labels ("system\n...user\n...assistant\n").
        # The answer is what follows the LAST "assistant\n". Strip trailing
        # end-of-text / special-token markers.
        if "assistant\n" in stdout:
            tail = stdout.rsplit("assistant\n", 1)[-1]
        else:
            tail = stdout
        for marker in ("[end of text]", _IM_END, _IM_START, "<|endoftext|>"):
            idx = tail.find(marker)
            if idx != -1:
                tail = tail[:idx]
        return _strip_think(tail).strip()
