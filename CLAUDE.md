# Mini WSA

A Java/Spring Boot security analytics pipeline (Akamai take-home assignment): ingests security
events, classifies/enriches them with attack type + threat score, stores them, and exposes
analytics APIs for stats and sample retrieval.

- [REQUIREMENTS.md](REQUIREMENTS.md) — the assignment spec (derived from `assignment.pdf`, the
  source of truth). Read this before implementing or changing any endpoint, enrichment rule, or
  response shape — it defines required fields, threat-score weights, attack-type mappings, and
  API contracts exactly as specified.
- [DESIGN.md](DESIGN.md) — implementation plan and technology choices (tech stack, storage
  justification, project structure, milestones).

- Follow guidelines by SKILL.md of spring boot java in this project.