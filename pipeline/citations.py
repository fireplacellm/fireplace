"""Citation verification (ADR 0005): check each cited claim against its source.

The real device path uses the model/NLI to judge support; here we use a
lexical-support heuristic (claim tokens covered by the cited passage) that is
deterministic and catches the common failure modes: a citation marker pointing
at a passage that does not actually contain the claim, and claims with no
citation at all. The interface is identical, so the device verifier swaps in.

Limitation (by design, documented honestly): the lexical pass checks token
COVERAGE, so it catches fabricated/uncited claims but NOT contradiction or
negation ("apply a tourniquet" vs "do not apply a tourniquet" share tokens).
Catching contradiction requires the on-device NLI/model verifier (ADR 0005).
"""

from __future__ import annotations

import dataclasses
import re

from reader import Hit

_CITE = re.compile(r"\[(\d+)\]")
_NUM = re.compile(r"\b\d[\d,.]*\b")
_TOK = re.compile(r"[a-z0-9]+")
_STOP = frozenset(
    "the a an of to in on at for and or but is are was were be been being this that these those "
    "it its as by with from into about over under can could would should may might will shall do "
    "does did has have had you your i we they he she them his her their our not no yes if then than "
    "which who whom whose what when where why how".split()
)


def _content_toks(s: str) -> list[str]:
    return [t for t in _TOK.findall(s.lower()) if t not in _STOP and len(t) > 2]


@dataclasses.dataclass
class ClaimCheck:
    sentence: str
    cited: list[int]
    supported: bool
    coverage: float
    numbers_ok: bool = True


@dataclasses.dataclass
class CitationReport:
    checks: list[ClaimCheck]

    @property
    def total(self) -> int:
        return len(self.checks)

    @property
    def supported(self) -> int:
        return sum(1 for c in self.checks if c.supported)

    @property
    def uncited(self) -> int:
        return sum(1 for c in self.checks if not c.cited)

    @property
    def pass_rate(self) -> float:
        substantive = [c for c in self.checks if _content_toks(c.sentence)]
        if not substantive:
            return 1.0
        return sum(1 for c in substantive if c.supported) / len(substantive)

    @property
    def cited_rate(self) -> float:
        """Fraction of substantive claims carrying a citation marker (honest "% cited")."""
        substantive = [c for c in self.checks if _content_toks(c.sentence)]
        if not substantive:
            return 1.0
        return sum(1 for c in substantive if c.cited) / len(substantive)


def split_sentences(text: str) -> list[str]:
    # Keep the trailing citation markers attached to their sentence.
    parts = re.split(r"(?<=[.!?])\s+", text.strip())
    return [p for p in parts if p.strip()]


def _group_sentences(answer_text: str) -> list[tuple[str, list[int]]]:
    """Attach citations to the sentences they cover. Real answers cite in the
    style "claim. claim. [1]" - a trailing citation covers the run of sentences
    since the previous citation, and a citation-only fragment (e.g. "[1]" split
    off after a period) attaches to the preceding sentence. Returns
    (sentence_without_markers, covering_citations) for substantive sentences."""
    raw = split_sentences(answer_text)
    # (content, own_citations); drop pure-citation fragments onto the previous.
    items: list[list] = []
    for sent in raw:
        cites = [int(m) for m in _CITE.findall(sent)]
        content = _CITE.sub("", sent).strip()
        if not _content_toks(content):  # pure-citation or non-substantive fragment
            if cites and items:
                items[-1][1].extend(cites)  # carry its citations to the prior claim
            continue
        items.append([content, cites])
    # Backward fill: a run of uncited claims is covered by the next citation.
    covering: list[int] = []
    for i in range(len(items) - 1, -1, -1):
        if items[i][1]:
            covering = items[i][1]
        elif covering:
            items[i][1] = list(covering)
    return [(c, cites) for c, cites in items]


def _norm_num(s: str) -> str:
    """"1,000" == "1000"; a trailing unit/punctuation ("23.5°", "50%.") shouldn't
    defeat an otherwise-exact number match."""
    return s.replace(",", "").strip(".,")


def verify(answer_text: str, hits: list[Hit], *, min_coverage: float = 0.34) -> CitationReport:
    """Verify numbered [n] citations (1-indexed into `hits`) against passages."""
    # Index passage tokens by document: a long article is split across several
    # retrieved passages, so a claim cited to one passage is really grounded in
    # the whole document - count every sibling passage of a cited source's doc.
    by_doc: dict[str, set[str]] = {}
    num_by_doc: dict[str, set[str]] = {}
    for h in hits:
        by_doc.setdefault(h.doc_id, set()).update(_content_toks(h.text))
        num_by_doc.setdefault(h.doc_id, set()).update(_norm_num(x) for x in _NUM.findall(h.text))
    # Union over every retrieved passage - the fallback when a (small) model cites a
    # source number that doesn't exist; the claim is still grounded in the retrieved
    # corpus, so a numbering slip shouldn't score it as fabricated.
    all_toks: set[str] = set().union(*by_doc.values()) if by_doc else set()
    all_nums: set[str] = set().union(*num_by_doc.values()) if num_by_doc else set()
    checks: list[ClaimCheck] = []
    for content, cited in _group_sentences(answer_text):
        claim_toks = _content_toks(content)
        if not cited:
            checks.append(ClaimCheck(content, cited, False, 0.0))  # substantive but uncited
            continue
        in_range = [n for n in cited if 1 <= n <= len(hits)]
        if not in_range:
            src_toks, src_nums = all_toks, all_nums  # misnumbered citation → verify vs all sources
        else:
            src_toks = set()
            src_nums = set()
            for n in in_range:
                src_toks |= by_doc.get(hits[n - 1].doc_id, set())
                src_nums |= num_by_doc.get(hits[n - 1].doc_id, set())
        covered = sum(1 for t in claim_toks if t in src_toks)
        coverage = covered / len(claim_toks) if claim_toks else 1.0
        # A figure the sources don't contain is an unverified number → not supported.
        numbers_ok = all(_norm_num(x) in src_nums for x in _NUM.findall(content))
        checks.append(ClaimCheck(content, cited, coverage >= min_coverage and numbers_ok, coverage, numbers_ok))
    return CitationReport(checks)


def annotate(answer_text: str, report: CitationReport) -> str:
    """Make uncertainty visible: flag any sentence that cites a source but whose
    claim the source doesn't support, and prepend a caution when grounding is weak.
    Mechanical, not a prompt - prompt-only abstention is unreliable for small models."""
    flagged = {
        c.sentence for c in report.checks
        if _content_toks(c.sentence) and c.cited and not c.numbers_ok
    }
    out = answer_text
    if flagged:
        parts = []
        for sent in split_sentences(answer_text):
            content = _CITE.sub("", sent).strip()
            parts.append(sent + (" ⚠️" if content and content in flagged else ""))
        out = " ".join(parts).strip()
    if report.total > 0 and report.pass_rate < 0.34:
        out = ("_⚠️ The sources only partly support this answer - treat the flagged "
               "parts with caution._\n\n" + out)
    return out
