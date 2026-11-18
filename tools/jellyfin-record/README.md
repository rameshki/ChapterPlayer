# jellyfin-record

Records the shapes of a Jellyfin server's answers so ChapterPlayer's test fixtures come from the real thing (review R39, plan
`docs/superpowers/plans/2026-11-19-jellyfin-j3.md`, task 1). Python standard library only.

## Run it

1. In Jellyfin's dashboard create a throwaway user (for example `devtest`) with access to the books library and the **Download**
   permission. Do not use a real account: the script plays and marks items on that user.
2. Set the three environment variables (never put them in a file in the repo):

   ```
   JF_URL=http://your-server:8096   JF_USER=devtest   JF_PASS=<its password>
   ```

3. `python tools/jellyfin-record/record.py --out <a folder outside the repo>` for the reads only, or add `--with-writes` to also
   record play sessions, user data and the played flag (they change state on the throwaway user).
4. Read the files in the output folder, then copy what is needed into `shared/src/androidHostTest/resources/fixtures/jellyfin/`.
5. Delete the throwaway user.

## What it guarantees

- Every answer is anonymised: ids, names, titles, paths, addresses, tokens and dates become invented values of the same shape;
  numbers, booleans and enum-like fields are kept.
- Before writing it checks that none of the real values (host, user name, password, token, user id, any real id or text) is left, and
  writes nothing if one is.
- The download call records the status and a few headers only (a `Range: bytes=0-0` request; the body is never read).
- It never follows a redirect and refuses to write inside the repository.

## Test

`python -m unittest discover -s tools/jellyfin-record` runs the anonymiser and the whole script against a fake Jellyfin server in
the same process.
