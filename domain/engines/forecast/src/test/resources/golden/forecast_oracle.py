# Independent §9.2 oracle for the golden file (issue 9.2). Decimal arithmetic, written from the SRS text.
from decimal import Decimal as D, getcontext, ROUND_HALF_EVEN
import datetime as dt, statistics
getcontext().prec = 50
today = dt.date(2026, 9, 19)
def spend(d):
    if d.toordinal() % 10 == 0: return 0
    v = D(180000) if d.weekday() >= 5 else D(100000)
    if d.day <= 5: v *= D('1.3')
    elif d.day >= 25: v *= D('0.8')
    return int(v)
hist = [(today - dt.timedelta(days=k)) for k in range(90, 0, -1)]
vals = [spend(d) for d in hist]
n = len(vals); cut = n * 1000 // 10000
s = sorted(vals)[cut:n-cut]; base = D(sum(s)) / D(len(s))
def med(xs):
    xs = sorted(xs); m = len(xs)//2
    return D(xs[m]) if len(xs) % 2 else (D(xs[m-1]) + D(xs[m])) / 2
def mean(xs): return D(sum(xs)) / D(len(xs)) if xs else D(0)
def ratio(p, w): return D(1) if w == 0 or p == 0 else p / w
allmed = med(vals); allmean = mean(vals)
we = ratio(med([v for d, v in zip(hist, vals) if d.weekday() >= 5]), allmed)
wd = ratio(med([v for d, v in zip(hist, vals) if d.weekday() < 5]), allmed)
sp = ratio(mean([v for d, v in zip(hist, vals) if d.day <= 5]), allmean)
mi = ratio(mean([v for d, v in zip(hist, vals) if 5 < d.day < 25]), allmean)
tr = ratio(mean([v for d, v in zip(hist, vals) if d.day >= 25]), allmean)
def pred(d):
    x = base * (we if d.weekday() >= 5 else wd) * (sp if d.day <= 5 else tr if d.day >= 25 else mi)
    return int(x.quantize(D(1), rounding=ROUND_HALF_EVEN))
horizon = [today + dt.timedelta(days=k) for k in range(1, 91)]
end = horizon[-1]
def add_months(d, k):
    import calendar
    y, m = divmod(d.month - 1 + k, 12); y += d.year; m += 1
    return dt.date(y, m, min(d.day, calendar.monthrange(y, m)[1]))
items = []
for label, amt, cad, nxt in [("Salary", 6000000, "M", dt.date(2026,10,1)), ("Rent", -2000000, "M", dt.date(2026,10,5)), ("Maid", -50000, "W", dt.date(2026,9,22))]:
    k = 0
    while True:
        d = add_months(nxt, k) if cad == "M" else nxt + dt.timedelta(weeks=k)
        if d > end: break
        if d >= horizon[0]: items.append((d, amt, label))
        k += 1
items.append((dt.date(2026,11,10), -1500000, "Laptop"))
opening = 2500000
by = {}
for d, a, _ in items: by[d] = by.get(d, 0) + a
run = opening; exp = []; preds = []
for d in horizon:
    p = pred(d); preds.append(p); run += by.get(d, 0) - p; exp.append(run)
print("base", int(base.quantize(D(1), rounding=ROUND_HALF_EVEN)))
print("predicted_first_14", ",".join(str(p) for p in preds[:14]))
print("predicted_total", sum(preds))
print("expected_day_30", exp[29]); print("expected_day_60", exp[59]); print("expected_day_90", exp[89])
print("scheduled_count", len(items))
print("scheduled_income", sum(a for _, a, _ in items if a > 0))
print("scheduled_outflow", -sum(a for _, a, _ in items if a < 0))
print("ratios", we, wd, sp, mi, tr, sep="\n  ")
