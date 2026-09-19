# Frozen backtest dataset generator for issue 9.2 (AI-FCT). Seeded; run once; output committed.
import random, math, datetime as dt
rng = random.Random(20260919)
out = ["# Frozen backtest ledgers for :domain:engines:forecast (issue 9.2; §21.5 forecast backtests).",
       "# SYNTHETIC — generated once by a seeded script (seed 20260919) and committed; never regenerated to",
       "# make a test pass. No real user data exists off-device (P-01), so the ledgers model the shapes the",
       "# §9.2 method claims to capture — weekday/weekend differences, a pay-cycle curve, heavy-tailed noise,",
       "# zero-spend days, rare shocks — plus two it does not model (drift, a mid-horizon regime change), so",
       "# the gate is not measuring the model against its own assumptions alone.",
       "#",
       "# Format: `ledger <id>` then `today`, `opening`, `commitment label|paise|CADENCE|nextDue` lines,",
       "# `history date:paise` (the 90 days up to yesterday, everyday spend) and `actual date:paise` (the 90",
       "# forecast days' real everyday spend). Amounts are paise (MNY-001)."]
def spend(day, p):
    if rng.random() < p['zero']: return 0
    v = p['base'] * (p['weekend'] if day.weekday() >= 5 else 1.0)
    if day.day <= 5: v *= p['spike']
    elif day.day >= 25: v *= p['trough']
    v *= math.exp(rng.gauss(-p['sigma']**2/2, p['sigma']))
    if rng.random() < p['shock_p']: v *= p['shock_x']
    return int(round(v * 100))
for i in range(20):
    today = dt.date(2026, 1, 1) + dt.timedelta(days=rng.randint(0, 300))
    p = dict(base=rng.uniform(200, 2500), weekend=rng.uniform(1.0, 1.8), spike=rng.uniform(1.0, 1.5),
             trough=rng.uniform(0.6, 1.0), sigma=rng.uniform(0.3, 0.8), zero=rng.uniform(0.05, 0.3),
             shock_p=0.02, shock_x=rng.uniform(4, 10))
    drift = 0.0012 if i % 5 == 3 else 0.0   # ~+11% over 90 days in 4 ledgers
    regime = (i % 7 == 5)                   # 3 ledgers: spending steps up 25% at day 45
    salary = rng.randint(40_000, 200_000) * 100
    rent = -rng.randint(8_000, 40_000) * 100
    opening = rng.randint(5_000, 300_000) * 100
    out.append(f"ledger L{i:02d}")
    out.append(f"today {today}")
    out.append(f"opening {opening}")
    out.append(f"commitment Salary|{salary}|MONTHLY|{today.replace(day=1) + dt.timedelta(days=32) - dt.timedelta(days=(today.replace(day=1)+dt.timedelta(days=32)).day - 1)}")
    out.append(f"commitment Rent|{rent}|MONTHLY|{(today + dt.timedelta(days=rng.randint(1, 28)))}")
    hist = []
    for k in range(90, 0, -1):
        d = today - dt.timedelta(days=k)
        hist.append(f"{d}:{spend(d, p)}")
    out.append("history " + ",".join(hist))
    act = []
    for k in range(1, 91):
        d = today + dt.timedelta(days=k)
        q = dict(p); q['base'] = p['base'] * (1 + drift * k) * (1.25 if regime and k > 45 else 1.0)
        act.append(f"{d}:{spend(d, q)}")
    out.append("actual " + ",".join(act))
open('/home/harish/windows/Desktop/My Projects/AI_personal_cfo/domain/engines/forecast/src/test/resources/backtest/ledgers.txt', 'w').write("\n".join(out) + "\n")
print("written", len(out))
