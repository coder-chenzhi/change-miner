package com.alibaba.changeminer;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffEntry.ChangeType;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.Edit.Type;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.errors.AmbiguousObjectException;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathSuffixFilter;
import org.eclipse.jgit.util.io.DisabledOutputStream;

public class LineLevelChangeMiner {

	private Git git;

	/**
	 * initialize by an existing repository
	 * 
	 * @param dir
	 */
	public LineLevelChangeMiner(String dir) {
		try {
			// To open an existing repo
			this.git = Git.open(new File(dir));
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * initialize by cloning an repository
	 * 
	 * @param url
	 *            clone url
	 * @param dir
	 *            local dir
	 */
	public LineLevelChangeMiner(String url, String dir) {
		try {
			// Cloning the repo
			this.git = Git.cloneRepository().setURI(url).setDirectory(new File(dir)).call();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void getChangedFilesForFirstCommit(RevCommit startCommit)
			throws MissingObjectException, IncorrectObjectTypeException, CorruptObjectException, IOException {

		Set<String> changedArtifacts = new HashSet<String>();

		Repository repo = git.getRepository();
		TreeWalk tw = new TreeWalk(repo);
		tw.reset();
		tw.setRecursive(true);
		tw.addTree(startCommit.getTree());

		while (tw.next()) {
			changedArtifacts.add(tw.getPathString());
		}

		System.out.println(startCommit.getName());
		System.out.println(changedArtifacts.toString());

	}

	public void getChangeSet(String commitID) throws IOException {
		Repository repo = git.getRepository();

		RevWalk rw = new RevWalk(repo);
		rw.setRetainBody(false);

		RevCommit commit = rw.parseCommit(repo.resolve(commitID));

		Repository repository = git.getRepository();

		DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE);
		df.setRepository(repository);
		df.setDiffComparator(RawTextComparator.DEFAULT);
		df.setDetectRenames(true);

		if (commit.getParentCount() == 0) {
			getChangedFilesForFirstCommit(commit);
		}

		RevCommit parentCommit = commit.getParent(0);
		parentCommit = rw.parseCommit(parentCommit.getId());

		List<DiffEntry> diffs = df.scan(parentCommit.getTree(), commit.getTree());

		Set<String> changedArtifacts = new HashSet<String>();
		for (DiffEntry diff : diffs) {
			String path;
			if (ChangeType.DELETE.equals(diff.getChangeType())) {
				path = diff.getOldPath();
			} else {
				path = diff.getNewPath();
			}

			changedArtifacts.add(path);
		}
		rw.dispose();
		System.out.println(commit.getName());
		System.out.println(changedArtifacts.toString());
	}

	/**
	 * 
	 * @param startCommitID
	 *            inclusive
	 * @param endCommitID
	 *            inclusive
	 * @return
	 * @throws RevisionSyntaxException
	 * @throws AmbiguousObjectException
	 * @throws IOException
	 */
	private List<RevCommit> getCommitsInRange(String startCommitID, String endCommitID)
			throws RevisionSyntaxException, AmbiguousObjectException, IOException {

		List<RevCommit> commits = new ArrayList<RevCommit>();
		Repository repo = git.getRepository();

		RevWalk rw = new RevWalk(repo);
		rw.setRetainBody(false);

		RevCommit startCommit = rw.parseCommit(repo.resolve(startCommitID));
		RevCommit commit = rw.parseCommit(repo.resolve(endCommitID));

		commits.add(commit);

		while (!commit.equals(startCommit)) {
			commit = rw.parseCommit(commit.getParent(0));
			commits.add(commit);
		}

		// TODO 如果startCommit不是第一次commit的话，估计需要把startCommit之前的一次commit也返回
		rw.dispose();
		return commits;
	}

	public void getDiffDetails(String commitID) throws RevisionSyntaxException, MissingObjectException,
			IncorrectObjectTypeException, AmbiguousObjectException, IOException, GitAPIException {

		Repository repo = git.getRepository();
		boolean firstCommit = true;

		RevWalk rw = new RevWalk(repo);
		rw.setRetainBody(false);

		RevCommit commit = rw.parseCommit(ObjectId.fromString(commitID));
		AbstractTreeIterator newTreeParser = prepareTreeIterator(commit);
		AbstractTreeIterator oldTreeParser = new EmptyTreeIterator();
		if (commit.getParentCount() != 0) {
			firstCommit = false;
			oldTreeParser = prepareTreeIterator(rw.parseCommit(commit.getParent(0)));
		}

		// DiffCommand actually use DiffFormatter,
		// the latter can provide more powerful functions
		// List<DiffEntry> diff =
		// git.diff().setOldTree(oldTreeParser).setNewTree(newTreeParser).call();

		DiffFormatter df = new DiffFormatter(DisabledOutputStream.INSTANCE);

		df.setRepository(repo);
		df.setDiffComparator(RawTextComparator.DEFAULT);
		df.setDetectRenames(true);
		df.setPathFilter(PathSuffixFilter.create(".java"));

		List<DiffEntry> diffs = df.scan(oldTreeParser, newTreeParser);
		if (firstCommit) {
			for (DiffEntry entry : diffs) {
				System.out.println(entry + ", " + entry.getNewId());
			}
		} else {
			for (DiffEntry entry : diffs) {
				System.out.println("Entry: " + entry + ", from: " + entry.getOldId() + ", to: " + entry.getNewId());

				FileHeader fileHeader = df.toFileHeader(entry);
				EditList edits = fileHeader.toEditList();
				RawText oldText = new RawText(
						git.getRepository().open(entry.getOldId().toObjectId(), Constants.OBJ_BLOB).getBytes());
				RawText newText = new RawText(
						git.getRepository().open(entry.getNewId().toObjectId(), Constants.OBJ_BLOB).getBytes());
				for (Edit edit : edits) {
					if (edit.getType() == Type.DELETE) {
						System.out.println("Delete below content in " + entry.getOldPath());
						System.out.println(oldText.getString(edit.getBeginA(), edit.getEndA(), false));
					} else if (edit.getType() == Type.INSERT) {
						System.out.println("Insert below content in " + entry.getNewPath());
						System.out.println(newText.getString(edit.getBeginB(), edit.getEndB(), false));
					} else if (edit.getType() == Type.REPLACE) {
						System.out.println("Replace below content in " + entry.getNewPath());
						System.out.println(oldText.getString(edit.getBeginA(), edit.getEndA(), false));
						System.out.println("To below content in " + entry.getNewPath());
						System.out.println(newText.getString(edit.getBeginB(), edit.getEndB(), false));
					}
				}
			}
		}

		rw.dispose();
	}

	public void getDiffDetails(String startCommitID, String endCommitID) throws RevisionSyntaxException,
			MissingObjectException, IncorrectObjectTypeException, AmbiguousObjectException, IOException {

	}

	private AbstractTreeIterator prepareTreeIterator(RevCommit commit) throws IOException {
		// from the commit we can build the tree which allows us to construct
		// the TreeParser
		try (RevWalk walk = new RevWalk(git.getRepository())) {
			RevTree tree = walk.parseTree(commit.getTree().getId());

			CanonicalTreeParser oldTreeParser = new CanonicalTreeParser();
			try (ObjectReader oldReader = git.getRepository().newObjectReader()) {
				oldTreeParser.reset(oldReader, tree.getId());
			}

			walk.dispose();

			return oldTreeParser;
		}
	}

	public static void main(String[] args) throws Exception {
		String url = "https://github.com/golivax/JDX.git";
		String dir = "c:/tmp/jdx";
		String firstCommit = "ca44b718d43623554e6b890f2895cc80a2a0988f";
		String changeLogCommit = "ade219f11f8cabc983db12126bf35084842476b5";

		LineLevelChangeMiner jGitExample = new LineLevelChangeMiner(dir);

//		jGitExample.getDiffDetails(firstCommit);
		jGitExample.getDiffDetails(changeLogCommit);

	}

}
