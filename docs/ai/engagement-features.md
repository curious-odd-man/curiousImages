# S-tier

## 1. On This Day

Your idea.

Enhancements:

* chronological slideshow across years
* "10 years ago → today"
* include nearby days (±3 days) if there are few photos
* compare children/pets growing up
* map of where you were that day

---

## 2. Today I Found...

Every launch, show one interesting discovery.

Examples:

> You photographed this bridge 17 times over 8 years.

> You haven't seen these photos since 2018.

> You visited this place in winter and summer.

> This person appears in 4,382 photos.

One interesting card per launch is enough.

---

## 3. Rediscover Forgotten Photos

Find images satisfying:

* never favorited
* never exported
* old
* visually good
* no duplicates
* low view count

Show:

> Hidden gems

---

## 4. Monthly Story

Instead of random memories:

> April 2019

with:

* 30 best photos
* timeline
* locations
* people
* events

Almost like automatic journals.

---

## 5. Person Timeline

# Discovery

## Places you've returned to

Cluster GPS.

Show:

> You've visited this beach 9 times.

---

## Then vs Now

Find photos from nearly identical viewpoints years apart.

Very satisfying.

---

## Hidden Patterns

Examples:

"You take most photos on Saturdays."

"You photograph sunsets much more than sunrises."

"August is your busiest month."

"Your most photographed city is..."

Small statistics.

---

# Organization helpers

## Inbox

Photos with no album.

---

## AI album suggestions

"I found 84 photos that probably belong together."

---

## Similar cleanup

Instead of exact duplicates:

```
98 almost identical photos

Keep best?
```

Huge quality-of-life feature.

---

## Screenshot cleanup

If detected.

---

## Low-quality finder

Blur
Eyes closed
Dark
Overexposed

---

# AI features

## Auto-generated album titles

Instead of

```
IMG_2024
```

suggest

```
Weekend in Prague
```

---

## Event detection

Cluster by:

* time
* location
* people

Create:

```
John's Birthday
```

without manual work.

---

## "Describe this photo"

Tiny local VLM.

Useful for search.

---

## Semantic search history

Remember searches.

```
dogs

winter

birthday

red flowers
```

---

## Ask your archive

Using local LLM.

Examples:

> Show all times Alice and Bob were together.

> When did I last visit Paris?

> Show snowy mountains.

---

# Fun features

## Random Memory

One click.

---

## Mystery Photo

Show one random image.

Guess:

> What year?

Reveal answer.

---

## This Never Happened Again

Interesting concept.

Find photos unlike everything else.

Maybe:

* camel
* eclipse
* hot air balloon

Rare memories.

---

# Statistics

People enjoy dashboards.

Examples:

* photos/year
* people photographed most
* countries
* cities
* travel heatmap
* day of week
* hour of day
* camera usage
* longest gap without taking photos

---

# AI-generated stories

Local LLM.

Example:

> Summer 2018 was centered around hiking in Norway with Anna. You visited Bergen, spent three days around the fjords,
> and photographed mostly landscapes.

Not perfect, but fun.

---

# One feature I think would be uniquely compelling

Since you already have **CLIP embeddings**, I'd build what I'd call an **Archive Explorer**.

Every time the app opens, present one card such as:

* "You photographed this person every Christmas for 12 years."
* "These two photos were taken 11 years apart in almost the same place."
* "You own 428 photos of bicycles."
* "This flower appears in photos from six different countries."
* "You unknowingly recreated this photo after nine years."
* "These five vacations all include the same backpack."
* "This is the first photo containing your dog."
* "This object disappeared from your photos after 2017."

Unlike "On This Day," which is based on the calendar, these are *semantic* discoveries extracted from the archive. The
space of possible insights is effectively unbounded, so users have a reason to return even when there isn't a relevant
anniversary. With face recognition, CLIP embeddings, timestamps, GPS, and EXIF, you already have most of the data needed
to generate these cards offline. It becomes a personalized "Did you know?" feed derived entirely from the user's own
collection rather than generic content.
