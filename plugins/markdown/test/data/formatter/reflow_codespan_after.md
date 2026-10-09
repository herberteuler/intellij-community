Updated FSharp.SystemTextJson from 0.19.13 to 1.0.5. This comes with a breaking change if you use it in other places:
When deserializing missing fields of type `option` or `voption`, an error will now be returned instead of deserializing
to null. Wrap such fields in `Skippable`.

Text before `code span` continues with punctuation.
