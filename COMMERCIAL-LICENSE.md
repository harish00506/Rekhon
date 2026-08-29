# Commercial licensing

Rekhon is dual-licensed. Most people need the free option and should stop
reading here.

---

## Which one do you need?

**You almost certainly do NOT need a commercial licence.** Take the AGPL if any
of these describe you:

- You use the app for your own money.
- You are studying the code, or building on it for a hobby, dissertation or
  experiment.
- You are forking it and publishing your fork's source under the AGPL.
- You are running a modified version for a charity, school or public body and
  are willing to publish the changes.

That covers nearly everyone. It costs nothing, needs no permission, and no one
has to be told.

**You need a commercial licence if you want to do any of the following without
publishing your source:**

- Ship a proprietary product built on Rekhon's code.
- Offer a hosted or SaaS service derived from it. *(The AGPL's network clause
  catches this even if you never distribute a binary — that is the difference
  between the AGPL and the plain GPL.)*
- Distribute a closed-source fork, or one under any licence other than the AGPL.
- Bundle it into a commercial offering whose terms are incompatible with
  copyleft — including, in practice, the Apple App Store.

## What a commercial licence gives you

The same code, released from the AGPL's source-sharing obligations, on terms
agreed in writing. Everything else — the warranty disclaimer, the limitation of
liability, and the fact that Rekhon is not financial advice and not registered
with SEBI — carries over unchanged. Those are in [TERMS.md](TERMS.md) and are not
negotiable, because they are statements of fact about what this software is.

## How to ask

Open an issue at <https://github.com/harish00506/Rekhon/issues>, or contact the
owner directly. Useful things to say:

- What you want to build, roughly.
- Whether you intend to distribute it, host it, or both.
- Whether you would be able to publish your modifications — if you can, you may
  not need this at all, and it is worth checking before either of us spends time
  on it.

Terms are agreed case by case. There is no published price list.

## Why it is set up this way

Two things were wanted at once, and they pull against each other.

The source is public because Rekhon's central claim — *nothing about your money
leaves your device* — is one no user can verify from a store listing. Publishing
the code is what turns that from marketing into something checkable. That
argument only works if the code is genuinely open, which is why the free option
is a real OSI-approved licence and not a look-but-don't-touch arrangement.

The AGPL is also what keeps improvements flowing back. Someone who takes this,
makes it better and ships it has to publish what they changed.

The commercial option exists for the case the AGPL does not serve: somebody who
wants to build on this but cannot open their own source. Rather than forbid them
or let them take it for nothing, they can come and ask. That is a conversation,
not a lawsuit.

## A note for contributors

Dual licensing only works while the copyright holder can grant both licences.
If contributors kept exclusive rights over their patches, no commercial licence
could be granted for any file they touched, and the model would quietly break.

[CONTRIBUTING.md](CONTRIBUTING.md) therefore asks contributors for a relicensing
grant while they keep their own copyright. If that is not acceptable to you,
please open an issue with the idea instead of a pull request — ideas are welcome
and carry no such problem.
