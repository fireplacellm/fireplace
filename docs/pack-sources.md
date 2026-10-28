# Knowledge pack sources & build plan

Where each offline pack's data comes from. Every pack is built from primary, openly
licensed datasets with our own pipeline (`corpus/` produces an FTS5 index + MiniLM
dense vectors + a signed manifest), then runs through the same verify, retrieve,
ground and cite path as Wikipedia.

Every pack is built from text to one passage per chunk.

| Pack | Primary source | License | Our build |
|---|---|---|---|
| **1. Wikipedia** | `wikimedia/wikipedia` (Hugging Face) | CC BY-SA 4.0 | 21k-article starter bundled; 146k-article clean full corpus as an optional upgrade |
| **2. First aid / health** | Wikipedia medical and first-aid articles (curated titles) | CC BY-SA 4.0 | A first-aid topic slice of the Wikipedia corpus |
| **3. Crypto** | Wikipedia crypto articles (optionally ethereum.org, consensus specs, EIPs) | CC BY-SA 4.0 / MIT / CC0 | A crypto topic slice of Wikipedia |
| **4. Travel** | Wikivoyage `yuiseki/wikivoyage-geotagged` (Hugging Face, geotagged guides) plus GeoNames cities1000 | CC BY-SA 4.0 (Wikivoyage) / CC BY 4.0 (GeoNames) | A Wikivoyage text pack; GeoNames cities as place-fact records |
| **5. arXiv papers** | arXiv abstracts and metadata, `Cornell-University/arxiv` (CC0) or HF mirrors | CC0 (metadata) | A category-filtered abstract subset (cs.AI / LG / CL); abstracts are the RAG content |

## Notes on the hard cases
- **First aid and crypto are topical slices of Wikipedia.** Fastest, cleanest, fully open:
  subset the existing `wikipedia-clean.fpack` by a curated topic list, no new downloads.
- **Travel.** Wikivoyage *guides* are text and fit RAG directly. A full places/POI layer
  (OSM/Overture POIs under ODbL) is a geo gazetteer rather than text RAG, needing lat/lon
  plus haversine lookup and a different retriever. We add GeoNames cities1000 as lightweight
  place-fact records now; a full OSM POI subsystem is a later addition.
- **arXiv full text** is ~1.1 TB and per-paper licensed, so it is not redistributable in bulk.
  Abstracts and metadata are CC0 and are what a research app actually needs to cite.

## Delivery
Packs are built locally, then bundled in the APK or hosted on a Hugging Face dataset (set
`Catalog.HOST`). Small topical packs (first-aid, crypto) are bundled; the full Wikipedia
corpus is a download-only upgrade.
