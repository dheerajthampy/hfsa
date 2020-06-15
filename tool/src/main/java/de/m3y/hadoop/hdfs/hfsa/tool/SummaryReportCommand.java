package de.m3y.hadoop.hdfs.hfsa.tool;

import java.io.IOException;
import java.io.PrintStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Pattern;

import de.m3y.hadoop.hdfs.hfsa.core.FSImageLoader;
import de.m3y.hadoop.hdfs.hfsa.core.FsVisitor;
import de.m3y.hadoop.hdfs.hfsa.util.SizeBucket;
import org.apache.hadoop.fs.permission.PermissionStatus;
import org.apache.hadoop.hdfs.server.namenode.FsImageProto;
import picocli.CommandLine;

/**
 * Computes a user/group file system summary
 */
@CommandLine.Command(name = "summary",
        description = "Generates an HDFS usage summary (default command if no other command specified)",
        mixinStandardHelpOptions = true,
        helpCommand = true,
        showDefaultValues = true
)
class SummaryReportCommand extends AbstractReportCommand {

    abstract static class AbstractStats {
        long sumFiles;
        final LongAdder sumDirectories = new LongAdder();
        final LongAdder sumSymLinks = new LongAdder();
        long sumBlocks;
        long sumFileSize;
        final SizeBucket fileSizeBuckets;

        static final Comparator<AbstractStats> COMPARATOR_BLOCKS = Comparator.comparingLong(o -> o.sumBlocks);
        static final Comparator<AbstractStats> COMPARATOR_SUM_FILES = Comparator.comparingLong(o -> o.sumFiles);
        static final Comparator<AbstractStats> COMPARATOR_SUM_DIRECTORIES = Comparator.comparingLong(o -> o.sumDirectories.longValue());
        static final Comparator<AbstractStats> COMPARATOR_SUM_FILE_SIZE = Comparator.comparingLong(o -> o.sumFileSize);

        AbstractStats() {
            fileSizeBuckets = new SizeBucket();
        }
    }

    static class UserStats extends AbstractStats {
        final String userName;

        UserStats(String userName) {
            this.userName = userName;
        }
    }

    static class GroupStats extends AbstractStats {
        final String groupName;

        GroupStats(String groupName) {
            this.groupName = groupName;
        }
    }

    static class OverallStats extends AbstractStats {
        int sumAclFiles;
        int sumAclDirectories;
        int sumNonAclFiles;
        int sumNonAclDirectories;
    }

    static class Report {
        final Map<String, GroupStats> groupStats;
        final Map<String, UserStats> userStats;
        final OverallStats overallStats;
        final String dirPath;

        Report(String dirPath) {
            this.dirPath = dirPath;
            groupStats = new ConcurrentHashMap<>();
            userStats = new ConcurrentHashMap<>();
            overallStats = new OverallStats();
        }

        GroupStats getOrCreateGroupStats(String groupName) {
            return groupStats.computeIfAbsent(groupName, GroupStats::new);
        }

        UserStats getOrCreateUserStats(String userName) {
            return userStats.computeIfAbsent(userName, UserStats::new);
        }
    }


    private static <T extends AbstractStats> List<T> sortStats(Collection<T> values, Comparator<? super AbstractStats> comparator) {
        final List<T> list = new ArrayList<>(values);
        list.sort(comparator);
        return list;
    }

    /**
     * Sort options.
     */
    enum SortOption {
        /**
         * file size
         */
        fs(AbstractStats.COMPARATOR_SUM_FILE_SIZE), // NOSONAR
        /**
         * file count
         */
        fc(AbstractStats.COMPARATOR_SUM_FILES), // NOSONAR
        /**
         * directory count
         */
        dc(AbstractStats.COMPARATOR_SUM_DIRECTORIES), // NOSONAR
        /**
         * block count
         */
        bc(AbstractStats.COMPARATOR_BLOCKS); // NOSONAR

        private final Comparator<AbstractStats> comparator;

        SortOption(Comparator<AbstractStats> comparator) {
            this.comparator = comparator;
        }

        public Comparator<AbstractStats> getComparator() {
            return comparator;
        }
    }

    @CommandLine.Option(names = {"-s", "--sort"},
            description = "Sort by <fs> size, <fc> file count, <dc> directory count or <bc> block count " +
                    "(default: ${DEFAULT-VALUE}). ")
    SortOption sort = SortOption.fs;

    @Override
    public void run() {
        final FSImageLoader loader = loadFsImage();
        if (null != loader) {
            for (String dir : mainCommand.dirs) {
                log.info("Visiting {} ...", dir);
                long start = System.currentTimeMillis();
                final Report report = computeReport(loader, dir);
                log.info("Visiting finished [{}ms].", System.currentTimeMillis() - start);

                doSummary(report);
            }
        }
    }


    void doSummary(Report report) {
        PrintStream out = mainCommand.out;
        // Overall
        final OverallStats overallStats = report.overallStats;

        out.println();
        final String title = "HDFS Summary : " + report.dirPath;
        out.println(title);
        out.println(FormatUtil.padRight('-', title.length()));
        out.println();

        // Overall
        final String[] bucketUnits = FormatUtil.toStringSizeFormatted(
                overallStats.fileSizeBuckets.computeBucketUpperBorders());
        final int[] maxLength = FormatUtil.max(
                FormatUtil.length(bucketUnits),
                FormatUtil.numberOfDigits(overallStats.fileSizeBuckets.get()));
        final String bucketFormatValue = FormatUtil.formatForLengths(maxLength, "d");
        final String bucketFormatHeader = FormatUtil.formatForLengths(maxLength, "s");
        final String bucketHeader = String.format(bucketFormatHeader, bucketUnits);

        out.println(
                "#Groups  | #Users      | #Directories | #Symlinks |  #Files     | Size [MB] | #Blocks   | File Size Buckets ");
        String header2ndLine =
                "         |             |              |           |             |           |           | " + bucketHeader;
        out.println(header2ndLine);
        out.println(FormatUtil.padRight('-', header2ndLine.length()));

        out.print(
                "Directories " + overallStats.sumDirectories.longValue() + "\n" +
                        "Files: " + overallStats.sumFiles + "\n" +
                        "Acl files " + overallStats.sumAclFiles + "\n" +
                        "Non Acl files " + overallStats.sumNonAclFiles + "\n" +
                        "Acl Directories " + overallStats.sumAclDirectories + "\n" +
                        "Non Acl Directories " + overallStats.sumNonAclDirectories + "\n");

//        out.println(String.format("%8d | %11d | %12d | %9d | %10d | %9d | %9d | %s",
//                report.groupStats.size(), report.userStats.size(),
//                overallStats.sumDirectories.longValue(), overallStats.sumSymLinks.longValue(),
//                overallStats.sumFiles, overallStats.sumFileSize / 1024L / 1024L,
//                overallStats.sumBlocks,
//                String.format(bucketFormatValue,
//                        FormatUtil.boxAndPadWithZeros(maxLength.length, overallStats.fileSizeBuckets.get()))
//        ));
        out.println();

        // Groups
        out.println(String.format(
                "By group:     %8d | #Directories | #SymLinks | #File      | Size [MB] | #Blocks   | File Size Buckets",
                report.groupStats.size()));
        header2ndLine = "     " +
                "                  |              |           |            |           |           | " + bucketHeader;
        out.println(header2ndLine);
        out.println(FormatUtil.padRight('-', header2ndLine.length()));
        for (GroupStats stat : sortStats(report.groupStats.values(), sort.getComparator())) {
            out.println(String.format("%22s |   %10d | %9d | %10d | %9d | %9d | %s",
                    stat.groupName, stat.sumDirectories.longValue(), stat.sumSymLinks.longValue(),
                    stat.sumFiles, stat.sumFileSize / 1024L / 1024L,
                    stat.sumBlocks,
                    String.format(bucketFormatValue,
                            FormatUtil.boxAndPadWithZeros(maxLength.length, stat.fileSizeBuckets.get()))
            ));
        }

        // Users
        out.println();
        final List<UserStats> userStats = filterByUserName(report.userStats.values(), mainCommand.userNameFilter);
        out.println(String.format(
                "By user:      %8d | #Directories | #SymLinks | #File      | Size [MB] | #Blocks   | File Size Buckets",
                userStats.size()));
        header2ndLine = "     " +
                "                  |              |           |            |           |           | " + bucketHeader;
        out.println(header2ndLine);
        out.println(FormatUtil.padRight('-', header2ndLine.length()));
        for (UserStats stat : sortStats(userStats, sort.getComparator())) {
            out.println(String.format("%22s |   %10d | %9d | %10d | %9d | %9d | %s",
                    stat.userName, stat.sumDirectories.longValue(), stat.sumSymLinks.longValue(),
                    stat.sumFiles, stat.sumFileSize / 1024L / 1024L,
                    stat.sumBlocks,
                    String.format(bucketFormatValue,
                            FormatUtil.boxAndPadWithZeros(maxLength.length, stat.fileSizeBuckets.get()))
            ));
        }
    }

    static List<UserStats> filterByUserName(Collection<UserStats> userStats, String userNamePattern) {
        List<UserStats> filtered = new ArrayList<>(userStats);
        // user name
        if (null != userNamePattern && !userNamePattern.isEmpty()) {
            Pattern pattern = Pattern.compile(userNamePattern);
            filtered.removeIf(u -> !pattern.matcher(u.userName).find());
        }
        return filtered;
    }

    Report computeReport(FSImageLoader loader, String dirPath) {
        final Report report = new Report(dirPath);
        final OverallStats overallStats = report.overallStats;

        final FsVisitor visitor = new FsVisitor() {
            @Override
            public void onFile(FsImageProto.INodeSection.INode inode, String path) {
                FsImageProto.INodeSection.INodeFile f = inode.getFile();

                PermissionStatus p = loader.getPermissionStatus(f.getPermission());

                final long fileSize = FSImageLoader.getFileSize(f);
                final long fileBlocks = f.getBlocksCount();
                synchronized (overallStats) {
                    overallStats.fileSizeBuckets.add(fileSize);
                    overallStats.sumBlocks += fileBlocks;
                    overallStats.sumFileSize += fileSize;
                    overallStats.sumFiles++;

                    if (p.getPermission().getStickyBit())
                        overallStats.sumAclFiles++;
                    else
                        overallStats.sumNonAclFiles++;
                }

                // Group stats
                final String groupName = p.getGroupName();
                final GroupStats groupStat = report.getOrCreateGroupStats(groupName);
                synchronized (groupStat) {
                    groupStat.sumFiles++;
                    groupStat.sumFileSize += fileSize;
                    groupStat.fileSizeBuckets.add(fileSize);
                    groupStat.sumBlocks += fileBlocks;
                }

                // User stats
                final String userName = p.getUserName();
                final UserStats userStat = report.getOrCreateUserStats(userName);
                synchronized (userStat) {
                    userStat.sumFiles++;
                    userStat.sumFileSize += fileSize;
                    userStat.fileSizeBuckets.add(fileSize);
                    userStat.sumBlocks += fileBlocks;
                }
            }

            @Override
            public void onDirectory(FsImageProto.INodeSection.INode inode, String path) {
                FsImageProto.INodeSection.INodeDirectory d = inode.getDirectory();
                PermissionStatus p = loader.getPermissionStatus(d.getPermission());

                // Group stats
                final String groupName = p.getGroupName();
                final GroupStats groupStat = report.getOrCreateGroupStats(groupName);
                groupStat.sumDirectories.increment();

                // User stats
                final String userName = p.getUserName();
                final UserStats userStat = report.getOrCreateUserStats(userName);
                userStat.sumDirectories.increment();

                overallStats.sumDirectories.increment();

                if (p.getPermission().getStickyBit())
                    overallStats.sumAclDirectories++;
                else
                    overallStats.sumNonAclDirectories++;
            }

            @Override
            public void onSymLink(FsImageProto.INodeSection.INode inode, String path) {
                final FsImageProto.INodeSection.INodeSymlink symlink = inode.getSymlink();
                PermissionStatus p = loader.getPermissionStatus(symlink.getPermission());

                // Group stats
                final String groupName = p.getGroupName();
                final GroupStats groupStat = report.getOrCreateGroupStats(groupName);
                groupStat.sumSymLinks.increment();

                // User stats
                final String userName = p.getUserName();
                final UserStats userStat = report.getOrCreateUserStats(userName);
                userStat.sumSymLinks.increment();

                overallStats.sumSymLinks.increment();
            }
        };

        try {
            loader.visitParallel(visitor, dirPath);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        return report;
    }
}
