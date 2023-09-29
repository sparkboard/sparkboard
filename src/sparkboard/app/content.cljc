(ns sparkboard.app.content
  (:require [sparkboard.schema :as sch :refer [s-]]))

(sch/register!
  (merge
    {:prose/format {s- [:enum
                        :prose.format/html
                        :prose.format/markdown]}
     :prose/string {s- :string}
     :prose/as-map {s- [:map {:closed true}
                        :prose/format
                        :prose/string]}}

    {:content/badge {s- [:map {:closed true} :badge/label]}
     :badge/label   {s- :string}}))