---
title: "Money and needs change only through logged entries"
date: 2026-10-29
status: active
---

# Money and needs change only through logged entries

`Ledger` keeps whole dollars and refuses to go below zero; every credit and debit is a
`Transaction` (tick, day, time, amount, balance, reason), and each one is also a world event, so it
shows in the observation's recentEvents and the decision log. Needs are doubles 0 to 100 shown to
models as whole numbers rounded down. Rates are applied every game minute but logged as one
`NeedChange` per need and reason per game hour (a day is a few dozen lines, not thousands); one-off
effects are logged as they happen. The CLI writes `hours-NN.jsonl` (money and needs every game
hour), `money-NN.jsonl` and `needs-NN.jsonl` beside each bedroom decision log.
